package com.music.spotui.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStoresTest {

    private fun item(key: String, uri: String, title: String, artist: String) =
        LocalItem(key, uri, title, artist, "Album", 123_000L, "content://art/1")

    @Test fun localLikesSurviveARestartAsText() {
        val liked = listOf(
            item("k2", "content://media/2", "Second", "Artist B"),
            item("k1", "content://media/1", "First", "Artist A"),
        )
        val again = LocalLikes.decode(LocalLikes.encode(liked))
        assertEquals(liked, again)
        // Another version's file, or no items at all: nothing liked, no crash.
        assertEquals(emptyList<LocalItem>(), LocalLikes.decode("""{"version":99,"items":[]}"""))
        assertEquals(emptyList<LocalItem>(), LocalLikes.decode("""{"version":1}"""))
    }

    @Test fun localMetadataSurvivesARestartAsText() {
        val names = mapOf(
            "content://media/1" to Triple("My Title", "My Artist", "My Album"),
            "content://media/2" to Triple("Other", "", ""),
        )
        assertEquals(names, LocalMetadata.decode(LocalMetadata.encode(names)))
        // A blank title is no override (the file's own tag shows again).
        assertEquals(emptyMap<String, Triple<String, String, String>>(),
            LocalMetadata.decode("""{"version":1,"names":{"content://media/3":{"title":"","artist":"X","album":"Y"}}}"""))
        // Files written before the album field have none: the album is no override ("").
        assertEquals(mapOf("content://media/4" to Triple("T", "A", "")),
            LocalMetadata.decode("""{"version":1,"names":{"content://media/4":{"title":"T","artist":"A"}}}"""))
    }

    @Test fun importedPlaylistsSurviveARestartAsText() {
        val p = ImportedPlaylists.create("Evening Mix", "https://open.spotify.com/playlist/xyz", listOf(
            ImportedSong("", "AAA", "One", "Artist A", "Alb", 201_000L, "https://img/1"),
            ImportedSong("", "BBB", "Two", "Artist B", "Other", 182_000L, ""),
        ))
        // Every row got its own key at import.
        assertTrue(p.items.all { it.key.isNotBlank() })
        assertTrue(p.items[0].key != p.items[1].key)
        ImportedPlaylists.rename(p.id, "Renamed Mix")
        ImportedPlaylists.remove(p.id, p.items[0].key)
        val text = ImportedPlaylists.write()
        ImportedPlaylists.playlists.clear()
        ImportedPlaylists.read(text)
        val again = ImportedPlaylists.playlists.getValue(p.id)
        assertEquals("Renamed Mix", again.name)
        assertEquals("https://open.spotify.com/playlist/xyz", again.sourceUrl)
        assertEquals(1, again.items.size)
        assertEquals("BBB", again.items[0].spotifyTrackId)
        assertEquals(182_000L, again.items[0].durationMs)
        ImportedPlaylists.delete(p.id)
        assertTrue(ImportedPlaylists.list().isEmpty())
    }

    @Test fun anImportedSongsIsrcSurvivesARestartAsText() {
        val p = ImportedPlaylists.create("With Isrc", "https://open.spotify.com/playlist/isrc", listOf(
            ImportedSong("", "AAA", "One", "Artist A", "Alb", 201_000L, "", "USS1Z2500001"),
            ImportedSong("", "BBB", "Two", "Artist B", "Other", 182_000L, ""),
        ))
        val text = ImportedPlaylists.write()
        ImportedPlaylists.playlists.clear()
        ImportedPlaylists.read(text)
        val again = ImportedPlaylists.playlists.getValue(p.id)
        assertEquals("USS1Z2500001", again.items[0].isrc)
        assertEquals("", again.items[1].isrc)
        ImportedPlaylists.delete(p.id)
    }

    @Test fun anImportedPlaylistFileWrittenBeforeIsrcStillLoads() {
        // A v10 file: no "isrc" key anywhere. The songs load with "" and lose nothing else.
        ImportedPlaylists.read(
            """{"version":1,"playlists":[
                {"id":"old1","name":"Old Mix","source":"https://open.spotify.com/playlist/old","created":5,"cover":"",
                 "items":[{"key":"k1","track":"AAA","title":"One","artist":"Artist A","album":"Alb","duration":201000,"cover":"https://img/1"}]}
            ]}""",
        )
        val old = ImportedPlaylists.playlists.getValue("old1")
        assertEquals("Old Mix", old.name)
        assertEquals(1, old.items.size)
        assertEquals("AAA", old.items[0].spotifyTrackId)
        assertEquals("Alb", old.items[0].album)
        assertEquals("", old.items[0].isrc)
        ImportedPlaylists.delete("old1")
    }

    @Test fun storedSongsAreRepairedWithoutTouchingWhatTheyAlreadyCarry() {
        // The repair for playlists imported before imports kept the full reading (the
        // embed page's rows: no ISRC, no cover, no album): blanks are filled from the
        // fresh reading, a field a song already carries is never overwritten, a song
        // the reading does not know is left as it is, and the list itself never changes.
        val p = ImportedPlaylists.create("Repair", "https://open.spotify.com/playlist/bf", listOf(
            ImportedSong("", "AAA", "One", "Artist A", "", 201_000L, ""),
            ImportedSong("", "BBB", "Two", "Artist B", "Other", 182_000L, "", "USS1Z2500002"),
            ImportedSong("", "CCC", "Three", "Artist C", "Third", 100_000L, "https://img/own", "USS1Z2500004"),
        ))
        val isrcFilled = ImportedPlaylists.repair(p.id, mapOf(
            "AAA" to ImportedSong("", "AAA", "One", "Artist A", "Alb", 201_000L, "https://img/1", "USS1Z2500001"),
            "BBB" to ImportedSong("", "BBB", "Two", "Artist B", "Other (Deluxe)", 182_000L, "https://img/2", "USXXX2500099"),
            "CCC" to ImportedSong("", "CCC", "Three", "Artist C", "Third (Remaster)", 100_000L, "https://img/3", "USS1Z2500003"),
            "ZZZ" to ImportedSong("", "ZZZ", "Unknown", "Artist Z", "Nope", 90_000L, "https://img/z", "USS1Z2500099"),
        ))
        // Only AAA gained an ISRC: BBB and CCC already carried their own (and keep them,
        // with their own album names and CCC's own cover), and ZZZ is not a song of this
        // playlist, so it is not added.
        assertEquals(setOf("AAA"), isrcFilled)
        val after = ImportedPlaylists.playlists.getValue(p.id)
        assertEquals(3, after.items.size)
        assertEquals(listOf("AAA", "BBB", "CCC"), after.items.map { it.spotifyTrackId })
        assertEquals("USS1Z2500001", after.items[0].isrc)
        assertEquals("https://img/1", after.items[0].coverUrl)
        assertEquals("Alb", after.items[0].album)
        assertEquals("USS1Z2500002", after.items[1].isrc)
        assertEquals("https://img/2", after.items[1].coverUrl)
        assertEquals("Other", after.items[1].album)
        assertEquals("USS1Z2500004", after.items[2].isrc)
        assertEquals("https://img/own", after.items[2].coverUrl)
        assertEquals("Third", after.items[2].album)
        // And what was repaired is what a restart reads back.
        val text = ImportedPlaylists.write()
        ImportedPlaylists.playlists.clear()
        ImportedPlaylists.read(text)
        val again = ImportedPlaylists.playlists.getValue(p.id)
        assertEquals("USS1Z2500001", again.items[0].isrc)
        assertEquals("https://img/1", again.items[0].coverUrl)
        assertEquals("Alb", again.items[0].album)
        ImportedPlaylists.delete(p.id)
    }

    @Test fun aRepairWithNothingFreshChangesNothing() {
        // The failed re-read's shape at the store: no fresh songs (or none that match),
        // so nothing is filled, nothing is reported repaired, and the stored list is
        // byte-for-byte the list that was there.
        val p = ImportedPlaylists.create("NoFresh", "https://open.spotify.com/playlist/nf", listOf(
            ImportedSong("", "AAA", "One", "Artist A", "", 201_000L, ""),
        ))
        val before = ImportedPlaylists.write()
        assertEquals(emptySet<String>(), ImportedPlaylists.repair(p.id, emptyMap()))
        assertEquals(emptySet<String>(), ImportedPlaylists.repair(p.id, mapOf(
            "ZZZ" to ImportedSong("", "ZZZ", "Unknown", "Artist Z", "Nope", 90_000L, "https://img/z", "USS1Z2500099"),
        )))
        assertEquals(before, ImportedPlaylists.write())
        ImportedPlaylists.delete(p.id)
    }

    @Test fun anImportedSongIsImportedOncePerTrackId() {
        // The store's no-duplicates rule, at import: a source list naming the same track
        // twice imports it once (first listing first); a song with no track id cannot be
        // told apart from another, so both of those stay.
        val p = ImportedPlaylists.create("Dupes", "https://open.spotify.com/playlist/dup", listOf(
            ImportedSong("", "AAA", "One", "Artist A", "Alb", 201_000L, ""),
            ImportedSong("", "AAA", "One (again)", "Artist A", "Alb", 201_000L, ""),
            ImportedSong("", "", "No Id", "Artist B", "Alb", 100_000L, ""),
            ImportedSong("", "", "No Id Either", "Artist C", "Alb", 100_000L, ""),
        ))
        assertEquals(3, p.items.size)
        assertEquals("One", p.items[0].title)
        ImportedPlaylists.delete(p.id)
    }

    @Test fun playlistCoversSurviveARestartAsText() {
        // Local playlists: the cover path rides in the JSON; a file written before covers
        // (no field) reads as no cover.
        LocalPlaylists.read(
            """{"version":1,"playlists":[
                {"id":"p1","name":"With","created":7,"cover":"/data/covers/a.jpg","items":[]},
                {"id":"p2","name":"Before","created":8,"items":[]}
            ],"additions":{}}""",
        )
        assertEquals("/data/covers/a.jpg", LocalPlaylists.playlists.getValue("p1").cover)
        assertEquals("", LocalPlaylists.playlists.getValue("p2").cover)
        val text = LocalPlaylists.write()
        LocalPlaylists.playlists.clear()
        LocalPlaylists.read(text)
        assertEquals("/data/covers/a.jpg", LocalPlaylists.playlists.getValue("p1").cover)
        LocalPlaylists.delete("p1")
        LocalPlaylists.delete("p2")

        // Imported playlists, the same: with a cover, and without the field at all.
        val p = ImportedPlaylists.create("Covered", "https://open.spotify.com/playlist/cov", listOf(
            ImportedSong("", "AAA", "One", "Artist A", "Alb", 201_000L, ""),
        ))
        ImportedPlaylists.setCover(p.id, "/data/covers/b.jpg")
        val importedText = ImportedPlaylists.write()
        ImportedPlaylists.playlists.clear()
        ImportedPlaylists.read(importedText)
        assertEquals("/data/covers/b.jpg", ImportedPlaylists.playlists.getValue(p.id).cover)
        ImportedPlaylists.clearCover(p.id)
        assertEquals("", ImportedPlaylists.playlists.getValue(p.id).cover)
        ImportedPlaylists.delete(p.id)
        ImportedPlaylists.read(
            """{"version":1,"playlists":[{"id":"old","name":"Old","source":"https://open.spotify.com/playlist/old","created":3,"items":[]}]}""",
        )
        assertEquals("", ImportedPlaylists.playlists.getValue("old").cover)
        ImportedPlaylists.delete("old")
    }

    @Test fun aNameEditReachesPlaylistRowsAndAdditions() {
        LocalPlaylists.read(
            """{"version":1,"playlists":[{"id":"p1","name":"Mix","created":7,"items":[
                {"key":"k1","uri":"content://media/1","title":"Old","artist":"Old Artist","album":"A","duration":1000,"art":""},
                {"key":"k2","uri":"content://media/2","title":"Keep","artist":"Keep Artist","album":"A","duration":1000,"art":""}
            ]}],"additions":{"s1":[
                {"key":"k3","uri":"content://media/1","title":"Old","artist":"Old Artist","album":"A","duration":1000,"art":""}
            ]}}""",
        )
        LocalPlaylists.applyMetadata("content://media/1", "New", "New Artist")
        val playlist = LocalPlaylists.playlists.getValue("p1")
        assertEquals("New", playlist.items[0].title)
        assertEquals("New Artist", playlist.items[0].artist)
        assertEquals("Keep", playlist.items[1].title)
        assertEquals("New", LocalPlaylists.additions.getValue("s1")[0].title)
        assertTrue(LocalPlaylists.write().contains("\"title\":\"New\""))
    }
}
