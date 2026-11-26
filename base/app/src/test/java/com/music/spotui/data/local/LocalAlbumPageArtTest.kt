package com.music.spotui.data.local

import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Album-page rows (v24, the regression behind it): on Tyson's "Laroi unreleased"
 * page the header showed the cover while every row showed a placeholder, because
 * the header reads the art under the PAGE's album name (with its own first-track
 * fallback) while the rows read the chain under each file's own album tag — and
 * the two names differ. The page-keyed [LocalCovers.effectiveArt] overload is the
 * fix: rows resolve under the page's name and fall back to the header's art.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalAlbumPageArtTest {

    private val pageAlbum = "Laroi unreleased"
    private val coverPath = "/files/album_covers/laroi.jpg"
    private val coverAddress = Uri.fromFile(File(coverPath)).toString()

    /** The header's art address as both album screens compute it: the page's set cover first. */
    private fun headerArt(): String? =
        LocalCovers.albumCover(pageAlbum)?.let { Uri.fromFile(File(it)).toString() }
            ?: LocalCovers.borrowedArt(pageAlbum)

    @Test fun aRowWhoseTagNamesTheAlbumDifferentlyShowsThePagesCover() {
        // The cover lives under the page's name; the file's own tag says something else.
        LocalCovers.read(LocalCovers.encode(emptyMap(), mapOf(LocalCovers.albumKey(pageAlbum) to coverPath)))
        LocalCovers.noteBorrowedArt(emptyList())
        val uri = "content://media/101"
        val ownArt = "content://albumart/t101"
        LocalCovers.noteArtPresence(ownArt, false)
        // The track-keyed chain (the old row call) misses the cover entirely: the bug.
        assertEquals(ownArt, LocalCovers.effectiveArt(uri, "The Kid Laroi Unreleased", ownArt))
        // Read under the page's album, the same row shows the page's cover.
        assertEquals(coverAddress, LocalCovers.effectiveArt(uri, ownArt, pageAlbum, headerArt()))
    }

    @Test fun aRowFallsBackToTheHeadersArtWhenOnlyTheHeaderCanReachIt() {
        // No cover stored anywhere: the header shows the first file's own art (its raw
        // fallback), and an artless row must show that same art, not a placeholder.
        LocalCovers.read(LocalCovers.encode(emptyMap(), emptyMap()))
        LocalCovers.noteBorrowedArt(emptyList())
        val firstArt = "content://albumart/first"
        val uri = "content://media/102"
        val ownArt = "content://albumart/t102"
        LocalCovers.noteArtPresence(ownArt, false)
        assertEquals(firstArt, LocalCovers.effectiveArt(uri, ownArt, pageAlbum, firstArt))
    }

    @Test fun aTrackWithItsOwnArtKeepsItOnThePage() {
        LocalCovers.read(LocalCovers.encode(emptyMap(), mapOf(LocalCovers.albumKey(pageAlbum) to coverPath)))
        val uri = "content://media/103"
        val ownArt = "content://albumart/t103"
        LocalCovers.noteArtPresence(ownArt, true)
        assertEquals(ownArt, LocalCovers.effectiveArt(uri, ownArt, pageAlbum, headerArt()))
    }

    @Test fun aTrackWithItsOwnSetCoverKeepsItOnThePage() {
        val songPath = "/files/track_covers/song.jpg"
        LocalCovers.read(LocalCovers.encode(mapOf("content://media/104" to songPath), mapOf(LocalCovers.albumKey(pageAlbum) to coverPath)))
        val ownArt = "content://albumart/t104"
        LocalCovers.noteArtPresence(ownArt, false)
        assertEquals(
            Uri.fromFile(File(songPath)).toString(),
            LocalCovers.effectiveArt("content://media/104", ownArt, pageAlbum, headerArt()),
        )
    }

    @Test fun withNoHeaderArtTheRowIsExactlyAsBefore() {
        LocalCovers.read(LocalCovers.encode(emptyMap(), emptyMap()))
        LocalCovers.noteBorrowedArt(emptyList())
        val uri = "content://media/105"
        val ownArt = "content://albumart/t105"
        LocalCovers.noteArtPresence(ownArt, false)
        // Nothing stored, nothing lent, no header art: the file's own (placeholder-bound)
        // address stands, as it did before the page overload existed.
        assertEquals(ownArt, LocalCovers.effectiveArt(uri, ownArt, pageAlbum, null))
    }
}
