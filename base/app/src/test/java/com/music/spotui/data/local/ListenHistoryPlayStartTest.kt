package com.music.spotui.data.local

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The store half of the v34 rule: a play's record is written when the song STARTS (not
 * after 30 s, not when the next song begins), and the time it plays for is written into
 * that same record — one line per play, however short the play.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ListenHistoryPlayStartTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        ListenHistory.init(context)
        ListenHistory.clear()
        file = File(context.filesDir, "listen_history.jsonl")
    }

    private fun listen(key: String, title: String, atMs: Long, playedMs: Long = 0) =
        ListenHistory.Listen(atMs, key, title, "Artist", "Album", "", playedMs, ListenHistory.Source.LOCAL)

    private fun lines(): List<JSONObject> =
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test
    fun `a play is on disk from its start, before any time has played`() {
        assertTrue(ListenHistory.beginListen(listen("local:1", "Song", atMs = 5_000)))
        val all = ListenHistory.all()
        assertEquals(1, all.size)
        assertEquals(0L, all[0].playedMs)
        assertEquals(5_000L, all[0].atMs)
        assertEquals(1, lines().size)
        assertEquals(0L, lines()[0].getLong("ms"))
    }

    @Test
    fun `the played time is written into the same record, not a second one`() {
        ListenHistory.beginListen(listen("local:1", "Song", atMs = 5_000))
        ListenHistory.updateOpenListen(12_000)
        ListenHistory.endListen(83_000)
        assertEquals(1, lines().size)
        assertEquals(83_000L, lines()[0].getLong("ms"))
        assertEquals(83_000L, ListenHistory.all()[0].playedMs)
        // The recap counts the play once and keeps its minutes.
        val recap = ListenHistory.recap(0)
        assertEquals(1, recap.listens)
        assertEquals(1L, recap.minutes)
        assertEquals(1, recap.topTracks.size)
        assertEquals(1, recap.topTracks[0].count)
    }

    @Test
    fun `a play of a second counts - the 30 second rule is gone`() {
        ListenHistory.beginListen(listen("local:1", "Brief", atMs = 1_000))
        ListenHistory.endListen(900)
        ListenHistory.beginListen(listen("local:2", "Next", atMs = 2_000))
        ListenHistory.endListen(500)
        assertEquals(2, lines().size)
        assertEquals(2, ListenHistory.recap(0).listens)
    }

    @Test
    fun `the same song started twice is two plays, and tops the recap`() {
        ListenHistory.beginListen(listen("spotify:track:x", "Loop", atMs = 1_000))
        ListenHistory.endListen(200_000)
        ListenHistory.beginListen(listen("spotify:track:x", "Loop", atMs = 300_000))
        ListenHistory.endListen(200_000)
        ListenHistory.beginListen(listen("spotify:track:y", "Once", atMs = 600_000))
        ListenHistory.endListen(200_000)
        val top = ListenHistory.recap(0).topTracks
        assertEquals("Loop", top[0].name)
        assertEquals(2, top[0].count)
        assertEquals(1, top[1].count)
    }

    @Test
    fun `a non-ASCII title survives a played-time update byte for byte`() {
        ListenHistory.beginListen(listen("local:9", "Beyoncé — Déjà Vu", atMs = 7_000))
        ListenHistory.updateOpenListen(45_000)
        assertEquals("Beyoncé — Déjà Vu", lines()[0].getString("title"))
        assertEquals(45_000L, lines()[0].getLong("ms"))
        assertEquals("Beyoncé — Déjà Vu", ListenHistory.all()[0].title)
    }

    @Test
    fun `an update with nothing open is a no-op`() {
        ListenHistory.updateOpenListen(10_000)
        ListenHistory.endListen(10_000)
        assertTrue(lines().isEmpty())
    }
}
