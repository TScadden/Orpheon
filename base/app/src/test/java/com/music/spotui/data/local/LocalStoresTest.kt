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
            "content://media/1" to ("My Title" to "My Artist"),
            "content://media/2" to ("Other" to ""),
        )
        assertEquals(names, LocalMetadata.decode(LocalMetadata.encode(names)))
        // A blank title is no override (the file's own tag shows again).
        assertEquals(emptyMap<String, Pair<String, String>>(),
            LocalMetadata.decode("""{"version":1,"names":{"content://media/3":{"title":"","artist":"X"}}}"""))
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
