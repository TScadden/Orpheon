package app.myspoti.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The pull of a phone file to the watch (Scadify v21): the buffer the player reads
 * while the file is still crossing, and the on-watch store that keeps pulled songs
 * under their hard 250 MB cap, least recently played first out. Robolectric (as the
 * other wear tests) because WatchPlay's order lives beside Android-facing code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchPullTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── PullBuffer ────────────────────────────────────────────────────────────────

    @Test fun readerWaitsForThePullAndThenReadsWhatCrossed() {
        val file = File(tmp.newFolder(), "song.part")
        val buffer = PullBuffer.fresh(file, expectedSize = 6)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val read = pool.submit<ByteArray> {
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4)
                while (true) {
                    val n = buffer.readAt(out.size().toLong(), chunk, 0, chunk.size)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            }
            // Nothing has crossed yet: the reader must still be waiting, not at an end.
            Thread.sleep(300)
            assertFalse("the reader finished before any byte crossed", read.isDone)
            buffer.append(byteArrayOf(1, 2, 3), 0, 3)
            buffer.append(byteArrayOf(4, 5, 6), 0, 3)
            buffer.complete()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), read.get(5, TimeUnit.SECONDS))
            assertTrue(buffer.done)
            assertEquals(6L, buffer.written)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun readerAtTheEndOfACompletedPullReadsMinusOne() {
        val buffer = PullBuffer.fresh(File(tmp.newFolder(), "song.part"), expectedSize = 2)
        buffer.append(byteArrayOf(9, 8), 0, 2)
        buffer.complete()
        assertEquals(-1, buffer.readAt(2, ByteArray(4), 0, 4))
        assertEquals(2, buffer.readAt(0, ByteArray(4), 0, 4))
    }

    @Test fun aFailedPullWakesTheReaderWithTheFailure() {
        val buffer = PullBuffer.fresh(File(tmp.newFolder(), "song.part"), expectedSize = 10)
        buffer.append(byteArrayOf(1), 0, 1)
        buffer.fail(PullFailed(PullFailed.Kind.GONE))
        try {
            buffer.readAt(5, ByteArray(4), 0, 4)
            fail("a read past a failed pull must throw")
        } catch (e: IOException) {
            assertTrue(e.cause is PullFailed)
        }
        assertEquals(PullFailed.Kind.GONE, (buffer.failure as PullFailed).kind)
    }

    @Test fun openLengthIsTheRestOfTheFileOrUnknownWhileCrossing() {
        val buffer = PullBuffer.fresh(File(tmp.newFolder(), "song.part"), expectedSize = 100)
        assertEquals(100L, buffer.openLength(0))
        assertEquals(30L, buffer.openLength(70))
        val unknown = PullBuffer.fresh(File(tmp.newFolder(), "other.part"), expectedSize = 0)
        assertEquals(-1L, unknown.openLength(0)) // C.LENGTH_UNSET: unknown while crossing
        unknown.append(byteArrayOf(1, 2), 0, 2)
        unknown.complete()
        assertEquals(2L, unknown.openLength(0))
    }

    @Test fun aCompletedBufferServesTheWholeFileAtOnce() {
        val file = File(tmp.newFolder(), "song.song")
        file.writeBytes(byteArrayOf(5, 4, 3, 2, 1))
        val buffer = PullBuffer.completed(file)
        assertTrue(buffer.done)
        assertEquals(5L, buffer.written)
        val dest = ByteArray(5)
        assertEquals(5, buffer.readAt(0, dest, 0, 5))
        assertArrayEquals(byteArrayOf(5, 4, 3, 2, 1), dest)
        assertEquals(-1, buffer.readAt(5, dest, 0, 5))
    }

    // ── WatchPullCache ────────────────────────────────────────────────────────────

    private fun cacheWithSong(cache: WatchPullCache, key: String, bytes: Int, modified: Long): File {
        val temp = cache.tempFile(key)
        temp.writeBytes(ByteArray(bytes))
        assertTrue(cache.commit(key))
        return cache.entryFile(key).also { it.setLastModified(modified) }
    }

    /** A kept song of a stated length without the bytes: the cap counts lengths, not content. */
    private fun cacheWithSparseSong(cache: WatchPullCache, key: String, bytes: Long, modified: Long): File {
        java.io.RandomAccessFile(cache.tempFile(key), "rw").use { it.setLength(bytes) }
        assertTrue(cache.commit(key))
        return cache.entryFile(key).also { it.setLastModified(modified) }
    }

    @Test fun keysBindAddressSizeAndModifiedTime() {
        val cache = WatchPullCache(tmp.newFolder())
        val a = cache.key("content://media/1", 100, 7)
        assertEquals(a, cache.key("content://media/1", 100, 7))
        assertNotEquals("a changed file must key differently", a, cache.key("content://media/1", 100, 8))
        assertNotEquals(a, cache.key("content://media/1", 101, 7))
        assertNotEquals(a, cache.key("content://media/2", 100, 7))
    }

    @Test fun commitKeepsTheSongAndEntryHandsItBack() {
        val cache = WatchPullCache(tmp.newFolder())
        val key = cache.key("content://media/1", 3, 7)
        cache.tempFile(key).writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(cache.commit(key))
        assertFalse(cache.tempFile(key).exists())
        val entry = cache.entry(key)
        assertTrue(entry != null && entry.length() == 3L)
        assertEquals(3L, cache.usage())
        assertNull(cache.entry("no-such-key"))
    }

    @Test fun aSongOverTheCapAloneIsNotKept() {
        val cache = WatchPullCache(tmp.newFolder())
        val key = cache.key("content://media/big", 1, 1)
        // A sparse file over the cap: length counts, content does not matter here.
        java.io.RandomAccessFile(cache.tempFile(key), "rw").use { it.setLength(WatchPullCache.CAP_BYTES + 1) }
        assertFalse(cache.commit(key))
        assertFalse(cache.tempFile(key).exists())
        assertEquals(0L, cache.usage())
    }

    @Test fun evictionDropsTheLeastRecentlyPlayedFirstAndSparesTheNewSong() {
        val cache = WatchPullCache(tmp.newFolder())
        val mb = 1024L * 1024
        // Three songs of 100 MB each: 300 MB against the 250 MB cap.
        val oldest = cacheWithSparseSong(cache, "k-oldest", 100 * mb, modified = 1_000)
        val middle = cacheWithSparseSong(cache, "k-middle", 100 * mb, modified = 2_000)
        val newest = cacheWithSparseSong(cache, "k-newest", 100 * mb, modified = 3_000)
        // commit() already evicted on the third song; make the state explicit instead:
        cache.evict(protect = "k-newest")
        assertTrue("the newest song is kept", newest.exists())
        assertFalse("the least recently played goes first", oldest.exists())
        assertTrue(cache.usage() <= WatchPullCache.CAP_BYTES)
        assertTrue(middle.exists())
    }

    @Test fun clearEmptiesTheStore() {
        val cache = WatchPullCache(tmp.newFolder())
        cacheWithSong(cache, "k1", 10, modified = 1_000)
        cache.tempFile("k2").writeBytes(byteArrayOf(1))
        cache.clear()
        assertEquals(0L, cache.usage())
        assertFalse(cache.tempFile("k2").exists())
    }

    // ── WatchPlay's order ─────────────────────────────────────────────────────────

    private fun track(name: String) = WatchTrack("", name, listOf("A"), "", 1_000L, null)

    @Test fun playOrderIsTheListOrder() {
        val tracks = listOf(track("One"), track("Two"), track("Three"))
        assertEquals(tracks, WatchPlay.order(tracks, shuffle = false))
    }

    @Test fun shuffleOrderIsTheSameSongsInSomeOrder() {
        val tracks = (1..20).map { track("Song $it") }
        val shuffled = WatchPlay.order(tracks, shuffle = true)
        assertEquals(tracks.size, shuffled.size)
        assertEquals(tracks.map { it.name }.toSet(), shuffled.map { it.name }.toSet())
    }

    @Test fun pullableNeedsAPhoneFileWithAnAddress() {
        val file = WatchTrack("", "F", listOf("A"), "", 1L, null, phoneOnly = true, localUri = "content://media/9")
        assertTrue(file.pullable)
        val noAddress = WatchTrack("", "F", listOf("A"), "", 1L, null, phoneOnly = true)
        assertFalse(noAddress.pullable)
        val catalogue = WatchTrack("spotify:track:x", "F", listOf("A"), "", 1L, null, localUri = "content://media/9")
        assertFalse("a catalogue song is never pulled", catalogue.pullable)
    }
}
