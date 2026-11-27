package com.music.spotui.extensions

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.myspoti.extensions.AudioStream
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.LocalPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HLS answers from an extension (v33): an answer that announces a playlist — SoundCloud's
 * only shape for full songs — was built into the player's item untyped, the factory read
 * the `myspoti-ext:` address (which says nothing) and chose the progressive source, and
 * the extractors broke on the playlist text: the song was "unavailable" though the
 * extension had answered and its playlist and segments all loaded.
 *
 * The chain pinned here is the production one, end to end: the typing rule
 * ([ExtensionAudio.itemMimeType], the very function [ExtensionAudio.localTrack] applies
 * to a kept answer and the read-ahead applies when the answer lands late), the item the
 * player itself builds ([LocalPlayer.item]), and the factory the player itself uses
 * ([DefaultMediaSourceFactory]) choosing the source for that item. The data source
 * factory behind it is a stub: source selection reads only the item, never the source.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtensionHlsPlaybackTest {

    private val song = SongsModel(
        id = 1, title = "Unstable", album = "Justice", singer = "Justin Bieber, The Kid LAROI",
        coverUri = "", url = "", spotifyTrackId = "unstable1", durationMs = 163_000,
    )

    /** The SoundCloud answer for "Unstable" as diagnosed live: an HLS stream endpoint, typed as a playlist. */
    private val hlsByType = AudioStream(
        url = "https://api-v2.soundcloud.com/media/soundcloud:tracks:1040089342/stream/hls",
        headers = emptyMap(), mimeType = "application/x-mpegURL", durationMs = 163_000,
        providerId = "io.github.tscadden.scadify.soundcloud", codec = "AAC",
    )

    /** An HLS answer that announces nothing: only its address tells. */
    private val hlsByAddress = AudioStream(
        url = "https://cdn.example.com/sc/1040089342/playlist.m3u8?sig=abc",
        headers = emptyMap(), mimeType = null, durationMs = 163_000,
        providerId = "io.github.tscadden.scadify.soundcloud",
    )

    /** A Melzo-shaped answer: a plain file, typed as one. */
    private val progressive = AudioStream(
        url = "https://cdn.example.com/audio/song.flac",
        headers = emptyMap(), mimeType = "audio/flac", durationMs = 163_000,
        providerId = "melzo.example", codec = "FLAC",
    )

    /** A DASH answer: read by the data source as one file, never typed for the factory. */
    private val dash = AudioStream(
        url = "", headers = emptyMap(), mimeType = "application/dash+xml", durationMs = 163_000,
        providerId = "dash.example", manifest = "base64manifest",
    )

    /** The track [ExtensionAudio.localTrack] builds for [song] once [stream] is its kept answer. */
    private fun trackFor(stream: AudioStream): LocalTrack =
        ExtensionAudio.localTrack(song).copy(mimeType = ExtensionAudio.itemMimeType(stream))

    /** The source the player's own factory makes for the item the player itself builds from [track]. */
    private fun sourceFor(track: LocalTrack): Any =
        DefaultMediaSourceFactory(StubFactory).createMediaSource(LocalPlayer.item(track))

    @Test fun anHlsMimeAnswerTypesItsItemAndSelectsTheHlsSource() {
        assertEquals(MimeTypes.APPLICATION_M3U8, ExtensionAudio.itemMimeType(hlsByType))
        val item = LocalPlayer.item(trackFor(hlsByType))
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
        assertTrue(sourceFor(trackFor(hlsByType)) is HlsMediaSource)
    }

    @Test fun anM3u8AddressWithoutATypeSelectsTheHlsSource() {
        assertEquals(MimeTypes.APPLICATION_M3U8, ExtensionAudio.itemMimeType(hlsByAddress))
        assertTrue(sourceFor(trackFor(hlsByAddress)) is HlsMediaSource)
    }

    @Test fun theAppleSpellingOfTheTypeIsHlsToo() {
        val apple = hlsByType.copy(mimeType = "application/vnd.apple.mpegurl")
        assertEquals(MimeTypes.APPLICATION_M3U8, ExtensionAudio.itemMimeType(apple))
        assertTrue(sourceFor(trackFor(apple)) is HlsMediaSource)
    }

    @Test fun aProgressiveAnswerStaysUntypedAndProgressive() {
        assertNull(ExtensionAudio.itemMimeType(progressive))
        val item = LocalPlayer.item(trackFor(progressive))
        assertNull(item.localConfiguration?.mimeType)
        assertTrue(sourceFor(trackFor(progressive)) is ProgressiveMediaSource)
    }

    @Test fun aDashAnswerStaysUntypedForTheFactory() {
        assertNull(ExtensionAudio.itemMimeType(dash))
        assertTrue(sourceFor(trackFor(dash)) is ProgressiveMediaSource)
    }

    @Test fun aSongWithNoAnswerKeptYetBuildsUntyped() {
        // The queue-wide case: only the picked song is asked at the tap, so the rest are
        // built untyped and retyped by the read-ahead when their answer lands.
        assertNull(ExtensionAudio.localTrack(song).mimeType)
    }

    @Test fun aLocalFileItemIsUntouched() {
        val file = LocalTrack(
            id = 7, uri = Uri.parse("file:///sdcard/Music/song.mp3"), title = "Song",
            artist = "Artist", album = "Album", durationMs = 163_000, artUri = Uri.EMPTY,
        )
        assertNull(LocalPlayer.item(file).localConfiguration?.mimeType)
        assertTrue(sourceFor(file) is ProgressiveMediaSource)
    }

    /** A data source that serves nothing: selection never opens it. */
    private class StubDataSource : BaseDataSource(false) {
        override fun open(dataSpec: DataSpec): Long = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = null
        override fun close() {}
    }

    private object StubFactory : DataSource.Factory {
        override fun createDataSource(): DataSource = StubDataSource()
    }
}
