package com.music.spotui.data.local

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The store's no-duplicates rule for local playlists: a file goes in at most once,
 * however it is added — one by one (false when it is already there, so the caller can
 * say so) or in a multi-select batch (the counts say how many went in and how many
 * were already there, repeats inside the batch itself included).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalPlaylistAddTest {

    private fun track(id: Long, uri: String) = LocalTrack(
        id = id, uri = Uri.parse(uri), title = "Song $id", artist = "Artist",
        album = "Album", durationMs = 1_000L, artUri = Uri.EMPTY,
    )

    @Test fun aCatalogSongGoesIntoALocalPlaylistByItsStreamAddress() {
        // The pickers' path for a catalog song (v23, package D): the row is the
        // song's stream address, which the local player resolves through the
        // extensions like any queued catalog song.
        val p = LocalPlaylists.create("Streamed")
        val song = com.music.spotui.data.entity.SongsModel(
            id = 1, title = "Song", album = "Album", singer = "Artist", coverUri = "",
            url = "spotify:track:ABC", spotifyTrackId = "ABC", durationMs = 200_000,
        )
        assertTrue(LocalPlaylists.addSong(p.id, song))
        val items = LocalPlaylists.playlists.getValue(p.id).items
        assertEquals(1, items.size)
        assertEquals("myspoti-ext://track/ABC", items[0].uri)
        assertEquals("Song", items[0].title)
        // Once only, like a file.
        assertFalse(LocalPlaylists.addSong(p.id, song))
        // A song with no Spotify id has nothing to stream by: refused, said so.
        assertFalse(LocalPlaylists.addSong(p.id, com.music.spotui.data.entity.SongsModel(
            id = 2, title = "Loose", album = "", singer = "Artist", coverUri = "", url = "",
        )))
        assertEquals(1, LocalPlaylists.playlists.getValue(p.id).items.size)
        LocalPlaylists.delete(p.id)
    }

    @Test fun aFileGoesIntoAPlaylistOnce() {
        val p = LocalPlaylists.create("Once")
        val one = track(1, "content://media/1")
        assertTrue(LocalPlaylists.add(p.id, one))
        assertFalse(LocalPlaylists.add(p.id, one))
        assertEquals(1, LocalPlaylists.playlists.getValue(p.id).items.size)
        LocalPlaylists.delete(p.id)
    }

    @Test fun aBatchReportsAddedAndAlreadyThere() {
        val p = LocalPlaylists.create("Batch")
        val one = track(1, "content://media/1")
        val two = track(2, "content://media/2")
        val three = track(3, "content://media/3")
        assertTrue(LocalPlaylists.add(p.id, one))
        // One already there, one repeated inside the batch itself, two new.
        val result = LocalPlaylists.addAll(p.id, listOf(one, two, three, two))
        assertEquals(AddResult(added = 2, skipped = 2), result)
        assertEquals(3, LocalPlaylists.playlists.getValue(p.id).items.size)
        // A batch of only what is there adds nothing and says so.
        assertEquals(AddResult(added = 0, skipped = 2), LocalPlaylists.addAll(p.id, listOf(one, two)))
        LocalPlaylists.delete(p.id)
    }

    @Test fun theOnThisDeviceRowsOfASpotifyPlaylistObeyTheSameRule() {
        val one = track(1, "content://media/1")
        val two = track(2, "content://media/2")
        assertTrue(LocalPlaylists.addToSpotify("sp1", one))
        assertFalse(LocalPlaylists.addToSpotify("sp1", one))
        assertEquals(AddResult(added = 1, skipped = 1), LocalPlaylists.addAllToSpotify("sp1", listOf(one, two)))
        LocalPlaylists.removeAllFromSpotify("sp1", LocalPlaylists.additions.getValue("sp1").map { it.key }.toSet())
        assertTrue(LocalPlaylists.additions["sp1"].isNullOrEmpty())
    }

    @Test fun removeAllTakesOutExactlyTheChosenRows() {
        val p = LocalPlaylists.create("Remove")
        LocalPlaylists.addAll(p.id, listOf(track(1, "content://media/1"), track(2, "content://media/2"), track(3, "content://media/3")))
        val items = LocalPlaylists.playlists.getValue(p.id).items
        LocalPlaylists.removeAll(p.id, setOf(items[0].key, items[2].key))
        val left = LocalPlaylists.playlists.getValue(p.id).items
        assertEquals(1, left.size)
        assertEquals("content://media/2", left[0].uri)
        LocalPlaylists.delete(p.id)
    }
}
