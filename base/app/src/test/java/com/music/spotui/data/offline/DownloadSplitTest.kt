package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSplitTest {

    @Test fun aMixedListSplitsIntoDownloadsAndPhoneFiles() {
        val split = DownloadSplit.of(listOf(
            "spotify:track:aaa" to "aaa",
            "local:content://media/1" to "",
            "spotify:track:bbb" to "bbb",
            "local:content://media/2" to "",
        ))
        assertEquals(2, split.downloadable)
        assertEquals(2, split.localFiles)
    }

    @Test fun duplicatesCountOnceAsTheButtonCountsThem() {
        val split = DownloadSplit.of(listOf(
            "spotify:track:aaa" to "aaa",
            "spotify:track:aaa" to "aaa",
        ))
        assertEquals(1, split.downloadable)
        assertEquals(0, split.localFiles)
    }

    @Test fun aDownloadIsNotAPhoneFile() {
        // A downloaded song also plays under a "local:" address in the player, but
        // its list row carries its track id — it is a download, not a phone file.
        val split = DownloadSplit.of(listOf("local:-123" to "aaa"))
        assertEquals(1, split.downloadable)
        assertEquals(0, split.localFiles)
    }

    @Test fun aCatalogKeyedSongDownloads() {
        val split = DownloadSplit.of(listOf("catalog:track" to "deezer.3135556"))
        assertEquals(1, split.downloadable)
        assertEquals(0, split.localFiles)
    }

    @Test fun anEmptyListSplitsToNothing() {
        assertEquals(DownloadSplit(0, 0), DownloadSplit.of(emptyList()))
    }
}
