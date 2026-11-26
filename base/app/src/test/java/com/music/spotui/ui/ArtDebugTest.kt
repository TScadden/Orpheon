package com.music.spotui.ui

import android.net.Uri
import com.music.spotui.data.local.LocalCovers
import com.music.spotui.data.local.LocalTrack
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The v25 diagnostic sheet: the report must carry the exact values the screens use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArtDebugTest {

    @Test fun reportShowsThePageValuesAndTheExactRowResult() {
        LocalCovers.read(LocalCovers.encode(emptyMap(), emptyMap()))
        LocalCovers.noteBorrowedArt(emptyList())
        val ownArt = "content://albumart/debug-row"
        val headerArt = "content://albumart/debug-header"
        LocalCovers.noteArtPresence(ownArt, false)
        val track = LocalTrack(
            id = 25,
            uri = Uri.parse("content://media/25"),
            title = "Not Fair",
            artist = "Laroi x Spooky Black",
            album = "The Kid Laroi Unreleased",
            durationMs = 123_000,
            artUri = Uri.parse(ownArt),
        )

        val report = buildArtDebugReport(
            pageAlbum = "Laroi unreleased",
            tracks = listOf(track),
            albumCoverPath = null,
            borrowedArt = null,
            headerArt = headerArt,
        )

        assertTrue(report.contains("pageAlbum: Laroi unreleased"))
        assertTrue(report.contains("albumCover(pageAlbum): null"))
        assertTrue(report.contains("borrowedArt(pageAlbum): null"))
        assertTrue(report.contains("headerArt: $headerArt"))
        assertTrue(report.contains("title: Not Fair"))
        assertTrue(report.contains("t.album: The Kid Laroi Unreleased"))
        assertTrue(report.contains("t.artUri: $ownArt"))
        assertTrue(report.contains("ownArtExists(t.artUri): false"))
        assertTrue(report.contains("row effectiveArt: $headerArt"))
        assertTrue(report.contains("rowResult vs headerArt: SAME"))
    }

    @Test fun probesClassifyFileAndUnsupportedAddresses() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("art-debug", ".jpg")
        try {
            assertEquals("File.exists() = true", probeArtAddress(context, file.toURI().toString()))
            assertEquals("File.exists() = false", probeArtAddress(context, File(file.parentFile, "missing-art-debug.jpg").absolutePath))
        } finally {
            file.delete()
        }
        assertEquals("unprobed", probeArtAddress(context, "https://example.invalid/cover.jpg"))
        assertEquals("unprobed", probeArtAddress(context, null))
    }
}
