/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.wear

import android.net.Uri
import com.music.spotui.data.local.LocalLikes
import com.music.spotui.data.local.LocalPlaylists
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The file a watch pull asks for (v33): in a cold process — the state this class puts
 * the phone's stores in before the call — [WatchAnswers.localFile] must find the file
 * from the store files alone (it inits and awaits both stores itself), and must still
 * refuse an address no answer exposes. Before the fix the same call read the stores
 * before their loads landed and answered null for a file the phone has: "error gone".
 *
 * Robolectric classes sharing a config share a sandbox, and with it the stores'
 * singletons: an earlier class (OnboardingTest) inits them against its own data dir
 * and loads them empty, and a store init'd once can never be init'd again. So the
 * test first puts both stores back to the state a cold process starts in — the files
 * this test wrote, nothing loaded, nothing in memory — and only then makes the call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WatchLocalFileTest {

    /** [store] back to a cold process's start, its file the one at [file] (see the class doc). */
    private fun cold(store: Any, file: File) {
        val cls = store.javaClass
        cls.getDeclaredField("file").apply { isAccessible = true }.set(store, file)
        cls.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(store, false)
    }

    @Test fun aListedFileOpensColdAndAnUnlistedOneDoesNot() {
        val app = RuntimeEnvironment.getApplication()
        val song = File(app.filesDir, "watch-song.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val uri = Uri.fromFile(song).toString()
        val item = JSONObject().put("key", "k1").put("uri", uri)
            .put("title", "Watch Song").put("artist", "Artist").put("album", "Album")
            .put("duration", 90_000).put("art", "")
        val list = JSONObject().put("id", "lp1").put("name", "Road Mix").put("created", 1)
            .put("cover", "").put("items", JSONArray().put(item))
        val root = JSONObject().put("version", 1).put("playlists", JSONArray().put(list))
            .put("additions", JSONObject())
        val playlistsFile = File(app.filesDir, "local_playlists.json")
        playlistsFile.writeText(root.toString())
        cold(LocalPlaylists, playlistsFile)
        LocalPlaylists.playlists.clear()
        // No likes file on this phone: the store loads empty, as a cold process finds it.
        cold(LocalLikes, File(app.filesDir, "local_likes.json"))
        LocalLikes.items.clear()

        val opened = WatchAnswers.localFile(app, uri)
        assertNotNull("a listed file must open in a cold process", opened)
        assertEquals(4L, opened!!.second)
        assertEquals(listOf(1, 2, 3, 4), opened.first.readBytes().map { it.toInt() })

        assertNull(WatchAnswers.localFile(app, "file:///sdcard/Music/not-exposed.mp3"))
        assertNull(WatchAnswers.localFile(app, ""))
    }
}
