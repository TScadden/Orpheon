package com.music.spotui.di

import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Liked Songs list's one judgement (v27): every liked song renders — phone
 * files judged by the phone's own like store, catalog rows by the account saved
 * map — so the list, the page's count and the play queue agree. Tyson's page
 * said "5 songs" and listed one: his four file likes shared the bare
 * "spotify:track:" key a blank id builds, a batch check had written it false,
 * and every file row was filtered out by it.
 */
class LikedRowShownTest {

    private fun localRow(uri: String) = SongsModel(
        id = 1, title = "A File", album = "", singer = "Me",
        coverUri = "", url = "local:$uri",
    )

    private fun catalogRow(id: String) = SongsModel(
        id = 2, title = "A Catalog Song", album = "", singer = "An Artist",
        coverUri = "", url = "spotify:track:$id|q", spotifyTrackId = id,
    )

    // The saved map as Tyson's phone held it: the bare key poisoned false by a
    // batch check that asked about nothing, one catalog like confirmed, one
    // catalog row unliked since.
    private val saved = mapOf(
        "spotify:track:" to false,
        "spotify:track:kept" to true,
        "spotify:track:gone" to false,
    )
    private val fileLikes = setOf("content://media/1", "content://media/2", "content://media/3", "content://media/4")
    private val isLocalLiked: (String) -> Boolean = { it in fileLikes }

    @Test fun aLikedFileRendersDespiteThePoisonedBareKey() {
        assertTrue(likedRowShown(localRow("content://media/1"), saved, isLocalLiked))
        // A file whose like was taken away leaves, judged by its own store.
        assertFalse(likedRowShown(localRow("content://media/9"), saved, isLocalLiked))
    }

    @Test fun catalogRowsKeepTheSavedMapsJudgement() {
        assertTrue(likedRowShown(catalogRow("kept"), saved, isLocalLiked))
        // Never judged by the map: on the page because it is liked; it stays.
        assertTrue(likedRowShown(catalogRow("unasked"), saved, isLocalLiked))
        assertFalse(likedRowShown(catalogRow("gone"), saved, isLocalLiked))
    }

    @Test fun aMixedLikedPageRendersEveryLikedSong() {
        // The page as built: four file likes and one catalog like — the header's
        // count, and the queue's set. The list filter (the expression both looks
        // use) must keep exactly that set.
        val page = listOf(
            localRow("content://media/1"),
            localRow("content://media/2"),
            localRow("content://media/3"),
            localRow("content://media/4"),
            catalogRow("kept"),
        )
        val shown = page.filter { likedRowShown(it, saved, isLocalLiked) }
        assertEquals(page.size, shown.size)
        assertEquals(page, shown)
    }
}
