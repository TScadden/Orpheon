/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.wear

import com.music.spotui.data.local.CatalogLike
import com.music.spotui.data.local.ImportedPlaylist
import com.music.spotui.data.local.ImportedSong
import com.music.spotui.data.local.LocalItem
import com.music.spotui.data.local.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accountless watch answers: with no music account signed in, the phone serves the watch
 * its own lists — local playlists, imported playlists, on-device likes — in the account
 * answer's shapes, with each row honest about whether the watch can play it (`phoneOnly`)
 * and carrying the ISRC where the store kept one.
 */
class WatchLocalAnswersTest {

    private fun localItem(title: String, artist: String = "Local Artist") = LocalItem(
        key = "k-$title", uri = "local:content://media/$title", title = title,
        artist = artist, album = "Phone Album", durationMs = 123_000L, artUri = "",
    )

    private fun importedSong(id: String, isrc: String, title: String = "Song $id") = ImportedSong(
        key = "k-$id", spotifyTrackId = id, title = title, artist = "Cat Artist",
        album = "Cat Album", durationMs = 200_000L, coverUrl = "https://img/$id.jpg", isrc = isrc,
    )

    @Test fun libraryListsLocalsBeforeImportsWithPhonePrefixes() {
        val entries = WatchLocalAnswers.libraryEntries(
            local = listOf(LocalPlaylist(id = "lp1", name = "Road Mix", items = listOf(localItem("A")), createdAt = 1L)),
            imported = listOf(ImportedPlaylist(id = "ip1", name = "Sunday", sourceUrl = "https://open.spotify.com/playlist/x", items = emptyList(), createdAt = 2L)),
        )
        assertEquals(2, entries.size)
        assertEquals("scadify:local:lp1", entries[0].getString("uri"))
        assertEquals("Road Mix", entries[0].getString("name"))
        assertEquals("On this phone", entries[0].getString("subtitle"))
        assertEquals("scadify:imported:ip1", entries[1].getString("uri"))
        assertEquals("Sunday", entries[1].getString("name"))
        assertEquals("Imported", entries[1].getString("subtitle"))
    }

    @Test fun localTracksAreAllPhoneOnlyWithNoUri() {
        val p = LocalPlaylist(id = "lp1", name = "Road Mix", items = listOf(localItem("A"), localItem("B")), createdAt = 1L)
        val rows = WatchLocalAnswers.localTracks(p)
        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertTrue("a phone file must be marked phone-only", row.getBoolean("phoneOnly"))
            assertEquals("", row.getString("uri"))
            assertEquals("", row.getString("isrc"))
            assertEquals("Phone Album", row.getString("album"))
        }
        assertEquals("A", rows[0].getString("name"))
        assertEquals("Local Artist", rows[0].getJSONArray("artists").getString(0))
    }

    @Test fun importedTracksCarryIdIsrcAndCover() {
        val p = ImportedPlaylist(
            id = "ip1", name = "Sunday", sourceUrl = "https://open.spotify.com/playlist/x", createdAt = 2L,
            items = listOf(importedSong("abc123", "USRC10000001")),
        )
        val row = WatchLocalAnswers.importedTracks(p).single()
        assertEquals("spotify:track:abc123", row.getString("uri"))
        assertEquals("USRC10000001", row.getString("isrc"))
        assertEquals("https://img/abc123.jpg", row.getString("cover"))
        assertFalse(row.getBoolean("phoneOnly"))
    }

    @Test fun importedTrackWithOnlyAnIsrcStillResolves() {
        val p = ImportedPlaylist(
            id = "ip1", name = "Sunday", sourceUrl = "", createdAt = 2L,
            items = listOf(importedSong("", "USRC10000002")),
        )
        val row = WatchLocalAnswers.importedTracks(p).single()
        assertEquals("", row.getString("uri"))
        assertEquals("USRC10000002", row.getString("isrc"))
        assertFalse("an ISRC alone is a resolvable identity", row.getBoolean("phoneOnly"))
    }

    @Test fun importedTrackWithNoIdentityIsPhoneOnly() {
        val p = ImportedPlaylist(
            id = "ip1", name = "Sunday", sourceUrl = "", createdAt = 2L,
            items = listOf(importedSong("", "")),
        )
        val row = WatchLocalAnswers.importedTracks(p).single()
        assertTrue(row.getBoolean("phoneOnly"))
    }

    @Test fun likedTracksPutCatalogLikesFirstThenPhoneFiles() {
        val rows = WatchLocalAnswers.likedTracks(
            catalog = listOf(
                CatalogLike(trackId = "xyz789", title = "Cat Song", artist = "Cat Artist", album = "Cat Album",
                    coverUri = "https://img/xyz.jpg", durationMs = 210_000L, url = ""),
                CatalogLike(trackId = "spotify:track:full1", title = "Full Uri", artist = "A", album = "",
                    coverUri = "", durationMs = 1L, url = ""),
            ),
            local = listOf(localItem("Liked File")),
        )
        assertEquals(3, rows.size)
        assertEquals("spotify:track:xyz789", rows[0].getString("uri"))
        assertFalse(rows[0].getBoolean("phoneOnly"))
        assertEquals("spotify:track:full1", rows[1].getString("uri"))
        assertEquals("Liked File", rows[2].getString("name"))
        assertTrue(rows[2].getBoolean("phoneOnly"))
        assertEquals("", rows[2].getString("uri"))
    }

    @Test fun dedupeKeyPrefersUriAndFallsBackToNameAndArtist() {
        val byUri = WatchLocalAnswers.track("spotify:track:abc", "Same", listOf("One"), "", 1L, "", "", false)
        assertEquals("u:spotify:track:abc", WatchLocalAnswers.dedupeKey(byUri))
        val byName = WatchLocalAnswers.track("", "My Song", listOf("The Artist"), "", 1L, "", "", true)
        assertEquals("n:my song|the artist", WatchLocalAnswers.dedupeKey(byName))
    }
}
