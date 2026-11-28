package com.music.spotui.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v34 counting rule: a play counts when a song STARTS (a tap, an auto-advance, a
 * shuffle landing, a repeat from the top), exactly once per start, for every kind of song;
 * a pause and resume of the same song is not a new start.
 */
class PlayCounterTest {

    private class FakeSink : PlayCounter.Sink {
        val begun = mutableListOf<ListenHistory.Listen>()
        val updates = mutableListOf<Long>()
        val ended = mutableListOf<Long>()
        override fun begin(listen: ListenHistory.Listen) { begun += listen }
        override fun update(playedMs: Long) { updates += playedMs }
        override fun end(playedMs: Long) { ended += playedMs }
    }

    private fun proto(key: String, title: String, source: ListenHistory.Source) =
        ListenHistory.Listen(0, key, title, "Artist", "Album", "", 0, source)

    private fun counter() = FakeSink().let { it to PlayCounter(it) }

    @Test
    fun `a tap to play counts once, at the start, for every kind of song`() {
        val kinds = listOf(
            Triple("local:12345", "Phone File", ListenHistory.Source.LOCAL),
            Triple("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "Imported Song", ListenHistory.Source.LOCAL),
            Triple("deezer:track:3135556", "Catalog Song", ListenHistory.Source.LOCAL),
            Triple("spotify:track:0TnOYISbd1XYRBk9myaseg", "Engine Song", ListenHistory.Source.SPOTIFY),
            Triple("local:-987", "Download", ListenHistory.Source.DOWNLOAD),
        )
        for ((key, title, source) in kinds) {
            val (sink, counter) = counter()
            val p = proto(key, title, source)
            counter.report(key, p, isPlaying = true, elapsedMs = 0, wallMs = 1_000)
            // More reports of the same uninterrupted play: no new count.
            counter.report(key, p, isPlaying = true, elapsedMs = 5_000, wallMs = 6_000)
            counter.report(key, p, isPlaying = true, elapsedMs = 9_000, wallMs = 10_000)
            assertEquals("$title counted once", 1, sink.begun.size)
            assertEquals(title, sink.begun[0].title)
            assertEquals("the record is stamped at its start", 1_000L, sink.begun[0].atMs)
            assertTrue("$title not ended while it plays", sink.ended.isEmpty())
        }
    }

    @Test
    fun `a song counts when it first plays, not while it is only cued or buffering`() {
        val (sink, counter) = counter()
        val p = proto("local:1", "Song", ListenHistory.Source.LOCAL)
        counter.report("local:1", p, isPlaying = false, elapsedMs = 0, wallMs = 100)
        counter.report("local:1", p, isPlaying = false, elapsedMs = 400, wallMs = 500)
        assertTrue(sink.begun.isEmpty())
        counter.report("local:1", p, isPlaying = true, elapsedMs = 900, wallMs = 1_000)
        assertEquals(1, sink.begun.size)
        assertEquals(1_000, sink.begun[0].atMs)
    }

    @Test
    fun `a song skipped past before it ever played never counts`() {
        val (sink, counter) = counter()
        val a = proto("local:1", "A", ListenHistory.Source.LOCAL)
        val b = proto("local:2", "B", ListenHistory.Source.LOCAL)
        val c = proto("local:3", "C", ListenHistory.Source.LOCAL)
        counter.report("local:1", a, isPlaying = true, elapsedMs = 0, wallMs = 0)
        counter.report("local:2", b, isPlaying = false, elapsedMs = 2_000, wallMs = 2_000)
        counter.report("local:3", c, isPlaying = true, elapsedMs = 3_000, wallMs = 3_000)
        assertEquals(listOf("A", "C"), sink.begun.map { it.title })
        assertEquals(listOf(2_000L), sink.ended)
    }

    @Test
    fun `auto-advance counts the next song and ends the one before`() {
        val (sink, counter) = counter()
        val a = proto("local:1", "A", ListenHistory.Source.LOCAL)
        val b = proto("local:2", "B", ListenHistory.Source.LOCAL)
        counter.report("local:1", a, isPlaying = true, elapsedMs = 0, wallMs = 0)
        counter.report("local:2", b, isPlaying = true, elapsedMs = 180_000, wallMs = 180_000)
        assertEquals(listOf("A", "B"), sink.begun.map { it.title })
        assertEquals(listOf(180_000L), sink.ended)
    }

    @Test
    fun `pause and resume is not a new start, and only the playing time accrues`() {
        val (sink, counter) = counter()
        val a = proto("local:1", "A", ListenHistory.Source.LOCAL)
        val b = proto("local:2", "B", ListenHistory.Source.LOCAL)
        counter.report("local:1", a, isPlaying = true, elapsedMs = 0, wallMs = 0)
        counter.report("local:1", a, isPlaying = false, elapsedMs = 10_000, wallMs = 10_000) // paused at 10 s
        counter.report("local:1", a, isPlaying = true, elapsedMs = 70_000, wallMs = 70_000) // resumed a minute later
        counter.report("local:2", b, isPlaying = true, elapsedMs = 85_000, wallMs = 85_000) // next song at +15 s of play
        assertEquals(1, sink.begun.count { it.title == "A" })
        assertEquals(listOf(25_000L), sink.ended)
        // The pause wrote the accrued 10 s back as it happened.
        assertEquals(listOf(10_000L), sink.updates)
    }

    @Test
    fun `a repeat from the top counts a new play`() {
        val (sink, counter) = counter()
        val a = proto("local:1", "A", ListenHistory.Source.LOCAL)
        counter.report("local:1", a, isPlaying = true, elapsedMs = 0, wallMs = 0)
        // The engine sees the same song start again (its repeat): end + begin.
        counter.report("local:1", a, isPlaying = true, restarted = true, elapsedMs = 200_000, wallMs = 200_000)
        assertEquals(2, sink.begun.size)
        assertEquals(listOf(200_000L), sink.ended)
        assertEquals(200_000L, sink.begun[1].atMs)
        // And the player's own repeat signal (LocalPlayer) does the same.
        counter.restart(elapsedMs = 400_000, wallMs = 400_000)
        assertEquals(3, sink.begun.size)
        assertEquals(listOf(200_000L, 200_000L), sink.ended)
    }

    @Test
    fun `a play under a second still counts - there is no minimum any more`() {
        val (sink, counter) = counter()
        val a = proto("local:1", "A", ListenHistory.Source.LOCAL)
        val b = proto("local:2", "B", ListenHistory.Source.LOCAL)
        counter.report("local:1", a, isPlaying = true, elapsedMs = 0, wallMs = 0)
        counter.report("local:2", b, isPlaying = true, elapsedMs = 800, wallMs = 800)
        assertEquals(listOf("A", "B"), sink.begun.map { it.title })
        assertEquals(listOf(800L), sink.ended)
    }

    @Test
    fun `muted reports (a Mix preview) never count and close the play that was open`() {
        val (sink, counter) = counter()
        val a = proto("local:1", "A", ListenHistory.Source.LOCAL)
        val b = proto("local:2", "B", ListenHistory.Source.LOCAL)
        counter.report("local:1", a, isPlaying = true, elapsedMs = 0, wallMs = 0)
        counter.report("local:2", b, isPlaying = true, muted = true, elapsedMs = 30_000, wallMs = 30_000)
        counter.report("local:1", a, isPlaying = true, muted = true, elapsedMs = 40_000, wallMs = 40_000)
        assertEquals(listOf("A"), sink.begun.map { it.title })
        assertEquals(listOf(30_000L), sink.ended)
        // The same song played for real afterwards counts as its own start.
        counter.report("local:1", a, isPlaying = true, elapsedMs = 50_000, wallMs = 50_000)
        assertEquals(2, sink.begun.size)
    }

    @Test
    fun `close ends the open play with the time it played`() {
        val (sink, counter) = counter()
        val a = proto("local:1", "A", ListenHistory.Source.LOCAL)
        counter.report("local:1", a, isPlaying = true, elapsedMs = 0, wallMs = 0)
        counter.close(elapsedMs = 42_000)
        assertEquals(listOf(42_000L), sink.ended)
        // Nothing is open afterwards: a stray restart is a no-op.
        counter.restart(elapsedMs = 50_000, wallMs = 50_000)
        assertEquals(1, sink.begun.size)
    }
}
