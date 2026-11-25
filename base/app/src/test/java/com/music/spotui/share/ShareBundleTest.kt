package com.music.spotui.share

import android.net.Uri
import com.music.spotui.data.local.LocalTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

/**
 * The share bundle's paper trail: the manifest survives being written and read back, the
 * zip keeps its songs byte for byte (stored, not deflated), and the import plan binds
 * the songs the receiver already owns instead of fetching them again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShareBundleTest {

    private fun track(entry: String, title: String, artist: String, album: String, durationMs: Long) =
        ShareTrack(entry, entry.substringAfterLast('/'), 1234L, "ab12", "audio/mpeg", durationMs, title, artist, album)

    private fun manifest(tracks: List<ShareTrack>) = ShareManifest(
        name = "Laroi unreleased", senderName = "Pixel 9a", createdAt = 42L,
        coverEntry = "cover.jpg", totalBytes = 2468L, tracks = tracks,
    )

    @Test fun manifestSurvivesARoundTrip() {
        val m = manifest(listOf(
            track("files/000.mp3", "High", "The Kid Laroi", "Unreleased", 187_000L),
            track("files/001.m4a", "Paris, Texas", "", "", 0L),
        ))
        assertEquals(m, ShareManifest.decode(m.encode()))
        // Not ours: another kind, another version, or no JSON at all.
        assertNull(ShareManifest.decode("""{"version":1,"kind":"other","tracks":[]}"""))
        assertNull(ShareManifest.decode("""{"version":99,"kind":"scadify-playlist","tracks":[]}"""))
        assertNull(ShareManifest.decode("not json"))
        // A playlist with no cover says so, and reads back that way.
        val bare = m.copy(coverEntry = null)
        assertEquals(bare, ShareManifest.decode(bare.encode()))
    }

    @Test fun zipKeepsSongsByteForByteAndStored() {
        val songA = ByteArray(100_000) { (it % 251).toByte() }
        val songB = ByteArray(5_000) { (255 - it % 256).toByte() }
        val statsA = ShareHashes.stats(ByteArrayInputStream(songA))
        val m = manifest(listOf(
            track("files/000.mp3", "A", "X", "", 1_000L).copy(size = statsA.size, sha256 = statsA.sha256),
        ))
        val out = ByteArrayOutputStream()
        ShareZip.write(listOf(
            ShareZip.Entry(ShareZip.MANIFEST_ENTRY, stored = false) { ByteArrayInputStream(m.encode().toByteArray()) },
            ShareZip.Entry(ShareZip.COVER_ENTRY, stored = false) { ByteArrayInputStream(byteArrayOf(9, 8, 7)) },
            ShareZip.Entry("files/000.mp3", stored = true, size = statsA.size, crc = statsA.crc) { ByteArrayInputStream(songA) },
            ShareZip.Entry("files/001.mp3", stored = true, size = songB.size.toLong(), crc = ShareHashes.stats(ByteArrayInputStream(songB)).crc) { ByteArrayInputStream(songB) },
        ), out)
        val bytes = out.toByteArray()

        // The manifest reads back out of the bundle stream.
        assertEquals(m, ShareZip.readManifest(ByteArrayInputStream(bytes)))
        assertNull(ShareZip.readManifest(ByteArrayInputStream("hello".toByteArray())))

        // The songs come back exactly, and the audio entry was stored, not deflated.
        val tmp = java.io.File.createTempFile("bundle", ".zip").apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        ZipFile(tmp).use { zip ->
            assertEquals(java.util.zip.ZipEntry.STORED, zip.getEntry("files/000.mp3").method)
            assertTrue(songA.contentEquals(zip.getInputStream(zip.getEntry("files/000.mp3")).readBytes()))
            assertTrue(songB.contentEquals(zip.getInputStream(zip.getEntry("files/001.mp3")).readBytes()))
            assertEquals(statsA.sha256, ShareHashes.sha256(zip.getInputStream(zip.getEntry("files/000.mp3"))))
        }
    }

    @Test fun planBindsWhatTheReceiverOwnsAndFetchesTheRest() {
        fun local(id: Long, title: String, artist: String, durationMs: Long) = LocalTrack(
            id = id, uri = Uri.parse("content://media/$id"), title = title, artist = artist,
            album = "Theirs", durationMs = durationMs, artUri = Uri.EMPTY,
        )
        val owned = local(7, "High", "The Kid Laroi", 187_500L) // half a second off: the same song
        val other = local(8, "Unrelated", "Someone", 90_000L)
        val m = manifest(listOf(
            track("files/000.mp3", "High", "The Kid Laroi", "Unreleased", 187_000L),
            track("files/001.mp3", "Bleed", "The Kid Laroi", "Unreleased", 200_000L),
        ))
        val plan = SharePlan.decide(m, listOf(owned, other))
        assertEquals(2, plan.size)
        val bind = plan[0] as ShareDecision.Bind
        assertEquals(owned, bind.existing)
        assertTrue(plan[1] is ShareDecision.Fetch)
        assertEquals(1, SharePlan.ownedCount(m, listOf(owned, other)))
        // Nothing owned: everything is fetched.
        assertTrue(SharePlan.decide(m, emptyList()).all { it is ShareDecision.Fetch })
        // One owned file cannot stand in for two rows of the playlist.
        val doubled = manifest(listOf(m.tracks[0], m.tracks[0].copy(entry = "files/002.mp3")))
        val plan2 = SharePlan.decide(doubled, listOf(owned))
        assertTrue(plan2[0] is ShareDecision.Bind)
        assertTrue(plan2[1] is ShareDecision.Fetch)
    }
}
