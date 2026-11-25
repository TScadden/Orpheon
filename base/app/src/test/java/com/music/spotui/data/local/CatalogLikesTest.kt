package com.music.spotui.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Catalog songs liked on this phone (CatalogLikes): the snapshot survives as text, a
 * song is liked once however often it is set, and an unlike takes exactly it out.
 */
class CatalogLikesTest {

    @Before fun reset() {
        CatalogLikes.read("""{"version":1,"items":[]}""")
    }

    private fun like(id: String, title: String = "Song $id") =
        CatalogLike(id, title, "Artist $id", "Album", "https://img/$id", 201_000L, "catalog:play:$id")

    @Test fun catalogLikesSurviveARestartAsText() {
        val liked = listOf(like("BBB", "Two"), like("AAA", "One"))
        assertEquals(liked, CatalogLikes.decode(CatalogLikes.encode(liked)))
        // Another version's file, or no items at all: nothing liked, no crash.
        assertEquals(emptyList<CatalogLike>(), CatalogLikes.decode("""{"version":99,"items":[]}"""))
        assertEquals(emptyList<CatalogLike>(), CatalogLikes.decode("""{"version":1}"""))
        // A row with no track id is no like at all.
        assertEquals(emptyList<CatalogLike>(), CatalogLikes.decode(
            """{"version":1,"items":[{"track":"","title":"X","artist":"","album":"","duration":0,"cover":"","url":""}]}"""))
    }

    @Test fun aSongIsLikedOnceAndNewestFirst() {
        CatalogLikes.set(like("AAA"), true)
        CatalogLikes.set(like("BBB"), true)
        // Liking again (a fresher snapshot) keeps one row, in its place.
        CatalogLikes.set(like("AAA", "One (remaster)"), true)
        assertEquals(listOf("BBB", "AAA"), CatalogLikes.items.map { it.trackId })
        assertTrue(CatalogLikes.isLiked("AAA"))
        assertFalse(CatalogLikes.isLiked("CCC"))
        assertFalse(CatalogLikes.isLiked(""))
    }

    @Test fun anUnlikeTakesExactlyThatSongOut() {
        CatalogLikes.set(like("AAA"), true)
        CatalogLikes.set(like("BBB"), true)
        CatalogLikes.set(like("AAA"), false)
        assertEquals(listOf("BBB"), CatalogLikes.items.map { it.trackId })
        // By id alone, as a caller without the snapshot unlikes.
        CatalogLikes.remove("BBB")
        assertTrue(CatalogLikes.items.isEmpty())
        // Unliking what is not there changes nothing.
        CatalogLikes.remove("BBB")
        assertTrue(CatalogLikes.items.isEmpty())
    }

    @Test fun aBlankTrackIdIsNeverStored() {
        CatalogLikes.set(like(""), true)
        assertTrue(CatalogLikes.items.isEmpty())
    }
}
