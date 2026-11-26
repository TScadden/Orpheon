/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.api

import com.music.spotui.data.entity.LibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditablePlaylistsTest {
    @Test fun aKeptListComesBackAsItWas() {
        val list = listOf(
            EditablePlaylist("a", "Road \"trip\"", 12, "https://i/a"),
            EditablePlaylist("spotify:user:u:folder:f", "Folder", 3, null, folderUri = "spotify:user:u:folder:f"),
            EditablePlaylist("b", "No cover", 0, null),
        )
        assertEquals(list, EditablePlaylists.decodeList(EditablePlaylists.encodeList(list)))
    }

    @Test fun anUnreadableListIsNoList() {
        assertNull(EditablePlaylists.decodeList("not json"))
    }

    @Test fun aKeptListKeepsItsSources() {
        val list = listOf(
            EditablePlaylist("a", "Account", 1, null),
            EditablePlaylist("b", "Imported", 2, null, source = PlaylistSource.IMPORTED),
            EditablePlaylist("c", "Local", 3, null, source = PlaylistSource.LOCAL),
        )
        assertEquals(list, EditablePlaylists.decodeList(EditablePlaylists.encodeList(list)))
        // A list kept before sources were recorded reads as the account's, as it was then.
        assertEquals(PlaylistSource.ACCOUNT,
            EditablePlaylists.decodeList("""[{"id":"a","name":"Old","count":1,"image":null,"folder":null}]""")!![0].source)
    }

    // The pickers' phone rows (v23, package D): the imported playlists, then the
    // local ones, each tagged with the store that holds it so a pick routes right.
    @Test fun thePhonePlaylistsOfferImportedThenLocal() {
        val imported = com.music.spotui.data.local.ImportedPlaylists.create("Imported One", "https://open.spotify.com/playlist/x", listOf(
            com.music.spotui.data.local.ImportedSong("", "AAA", "One", "A", "Alb", 1_000L, "https://img/1"),
        ))
        val local = com.music.spotui.data.local.LocalPlaylists.create("Local One")
        try {
            val phone = EditablePlaylists.phonePlaylists()
            val imp = phone.single { it.id == imported.id }
            assertEquals(PlaylistSource.IMPORTED, imp.source)
            assertEquals("Imported One", imp.name)
            assertEquals(1, imp.trackCount)
            assertEquals("https://img/1", imp.imageUrl)
            val loc = phone.single { it.id == local.id }
            assertEquals(PlaylistSource.LOCAL, loc.source)
            assertEquals(0, loc.trackCount)
            assertTrue(phone.indexOf(imp) < phone.indexOf(loc))
        } finally {
            com.music.spotui.data.local.ImportedPlaylists.delete(imported.id)
            com.music.spotui.data.local.LocalPlaylists.delete(local.id)
        }
    }

    private fun entry(uri: String) = LibraryEntry(spotifyId = uri.substringAfterLast(':'), name = "", subtitle = "", coverUri = "", isPlaylist = true, uri = uri)

    @Test fun aListedPlaylistSpotifyDoesNotCountIsAnOldList() {
        val p = "spotify:playlist:p"
        assertTrue(LibraryEdits.listedButNotSaved(p, emptySet(), listOf(entry(p))))
        assertFalse(LibraryEdits.listedButNotSaved(p, setOf(p), listOf(entry(p))))
        assertFalse(LibraryEdits.listedButNotSaved(p, emptySet(), listOf(entry("spotify:playlist:q"))))
    }
}
