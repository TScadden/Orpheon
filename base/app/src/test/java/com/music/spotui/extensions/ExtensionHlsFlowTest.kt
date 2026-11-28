package com.music.spotui.extensions

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.BaseTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.music.spotui.data.offline.ExtStreamCache
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * HLS answers, end to end (v34): v33 typed the item and the factory chose the HLS
 * source — and on the phone the song then sat at 0:00 forever, the playlist's
 * duration known, no error, no sound. Source SELECTION was all the v33 test
 * pinned; the segment path was never exercised. This test closes that gap: the
 * player's own factory ([DefaultMediaSourceFactory]) over the player's own data
 * source chain ([ExtStreamCache.Factory] → the offline switch → the platform HTTP
 * source) plays a fixture stream built from the real SoundCloud answer for
 * "Unstable" (its actual init segment and first two media segments, served by a
 * local server that records every request), and the test drives the period to
 * actual samples. If a segment request never happens, or bytes never become a
 * sample, the recorded requests and the failing stage say where the chain broke.
 *
 * The one seam not covered: the playlist leg of an `myspoti-ext:` item (through
 * the extension resolver and the safe media client), which v32 proved on the
 * phone — the progressive source received the playlist text through it and
 * choked. Everything past the parse — chunk source, segment opens through the
 * production chain, the fMP4 extractor — is production code here.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExtensionHlsFlowTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/hls/$name")?.readBytes()
            ?: throw AssertionError("missing test fixture hls/$name")

    /** Serves the fixture stream over HTTP/1.1, one connection per request, and records every path asked for. */
    private inner class FixtureServer {
        val requests = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val base: String = "http://127.0.0.1:${socket.localPort}"
        @Volatile private var closed = false

        private val playlist: String by lazy {
            """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="$base/init.mp4"
            #EXTINF:10.007800,
            $base/data000.m4s
            #EXTINF:10.007800,
            $base/data001.m4s
            #EXT-X-ENDLIST
            """.trimIndent() + "\n"
        }

        init {
            thread(isDaemon = true) {
                while (!closed) {
                    val s = try { socket.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { handle(s) }
                }
            }
        }

        private fun handle(s: Socket) {
            try {
                s.soTimeout = 10_000
                val input = s.getInputStream()
                val head = ByteArrayOutputStream()
                var window = 0
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    head.write(b)
                    window = (window shl 8) or b
                    if (window == 0x0D0A0D0A) break
                    if (head.size() > 16_384) break
                }
                val requestLine = head.toString(Charsets.US_ASCII).lineSequence().firstOrNull().orEmpty()
                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                requests += path
                val served: Pair<ByteArray, String>? = when (path) {
                    "/playlist.m3u8" -> playlist.toByteArray() to "application/vnd.apple.mpegurl"
                    "/init.mp4" -> fixture("init.mp4") to "audio/mp4"
                    "/data000.m4s" -> fixture("data000.m4s") to "audio/mp4"
                    "/data001.m4s" -> fixture("data001.m4s") to "audio/mp4"
                    else -> null
                }
                val out = s.getOutputStream()
                if (served == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: ${served.second}\r\nContent-Length: ${served.first.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(served.first)
                }
                out.flush()
            } catch (_: Exception) {
                // A client that went away mid-answer is not the test's business.
            } finally {
                runCatching { s.close() }
            }
        }

        fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    /** The one track of the fixture stream, always selected. */
    private class FixedSelection(group: TrackGroup, track: Int) : BaseTrackSelection(group, track) {
        override fun updateSelectedTrack(
            playbackPositionUs: Long, bufferedDurationUs: Long, availableDurationUs: Long,
            queue: List<androidx.media3.exoplayer.source.chunk.MediaChunk>,
            mediaChunkIterators: Array<androidx.media3.exoplayer.source.chunk.MediaChunkIterator>,
        ) = Unit

        override fun getSelectionReason(): Int = C.SELECTION_REASON_INITIAL
        override fun getSelectionData(): Any? = null
        override fun getSelectedIndex(): Int = 0
    }

    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun hlsItemFlowsThroughTheProductionChainToSamples() {
        val server = FixtureServer()
        try {
            val context = RuntimeEnvironment.getApplication()
            // The player's own factory over the player's own data source chain.
            val factory = DefaultMediaSourceFactory(ExtStreamCache.Factory(context))
            // The item v33 builds for the SoundCloud answer: typed as a playlist.
            // (Its address here is the playlist's own; production addresses the same
            // stream through the ext resolver — see the class doc.)
            val item = MediaItem.Builder()
                .setUri(Uri.parse("${server.base}/playlist.m3u8"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            val source = factory.createMediaSource(item)
            assertTrue("the factory must choose the HLS source, got $source", source is HlsMediaSource)

            var timeline: Timeline? = null
            source.prepareSource(object : MediaSource.MediaSourceCaller {
                override fun onSourceInfoRefreshed(s: MediaSource, t: Timeline) { timeline = t }
            }, null, PlayerId.UNSET)

            val deadline = System.currentTimeMillis() + 60_000
            fun beforeDeadline(stage: String) {
                if (System.currentTimeMillis() > deadline)
                    throw AssertionError("timed out at $stage; requests so far: ${server.requests}")
            }
            while (timeline == null) { beforeDeadline("source ready"); pump(); Thread.sleep(10) }
            val tl = timeline ?: throw AssertionError("unreachable: the loop only exits on a timeline")
            val window = Timeline.Window()
            tl.getWindow(0, window)
            assertTrue(
                "the playlist's duration must be known (two 10 s segments), got ${window.durationUs} us; requests: ${server.requests}",
                window.durationUs > 15_000_000,
            )

            val mediaPeriod = source.createPeriod(
                MediaSource.MediaPeriodId(tl.getUidOfPeriod(0)), DefaultAllocator(true, 64 * 1024), 0L,
            )
            var prepared = false
            mediaPeriod.prepare(object : MediaPeriod.Callback {
                override fun onPrepared(p: MediaPeriod) { prepared = true }
                override fun onContinueLoadingRequested(p: MediaPeriod) {
                    p.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
                }
            }, 0L)
            while (!prepared) {
                beforeDeadline("period prepared")
                mediaPeriod.maybeThrowPrepareError()
                pump(); Thread.sleep(10)
            }

            val groups = mediaPeriod.trackGroups
            assertTrue("the period must expose a track group; requests: ${server.requests}", groups.length > 0)
            val selection: ExoTrackSelection = FixedSelection(groups.get(0), 0)
            val streams = arrayOfNulls<SampleStream>(1)
            mediaPeriod.selectTracks(arrayOf(selection), booleanArrayOf(false), streams, booleanArrayOf(false), 0L)
            val stream = streams[0] ?: throw AssertionError("no sample stream was allocated; requests: ${server.requests}")
            mediaPeriod.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())

            val formatHolder = androidx.media3.exoplayer.FormatHolder()
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            var format: Format? = null
            var sampleBytes = -1
            var sampleTimeUs = -1L
            while (sampleBytes < 0) {
                beforeDeadline("first sample (format=$format, requests=${server.requests})")
                pump()
                stream.maybeThrowError()
                if (stream.isReady) {
                    buffer.clear()
                    when (stream.readData(formatHolder, buffer, 0)) {
                        C.RESULT_FORMAT_READ -> format = formatHolder.format
                        C.RESULT_BUFFER_READ -> {
                            sampleBytes = buffer.data?.limit() ?: 0
                            sampleTimeUs = buffer.timeUs
                        }
                    }
                }
                if (sampleBytes < 0) Thread.sleep(10)
            }
            assertTrue("a format must be read before samples; requests: ${server.requests}", format != null)
            assertTrue("the sample must carry bytes, got $sampleBytes; requests: ${server.requests}", sampleBytes > 0)
            assertTrue("the init segment must have been requested; requests: ${server.requests}",
                server.requests.any { it == "/init.mp4" })
            assertTrue("a media segment must have been requested; requests: ${server.requests}",
                server.requests.any { it == "/data000.m4s" })
            assertTrue("the sample's time must be at the stream's start, got $sampleTimeUs", sampleTimeUs in 0..5_000_000)
        } finally {
            server.close()
        }
    }
}
