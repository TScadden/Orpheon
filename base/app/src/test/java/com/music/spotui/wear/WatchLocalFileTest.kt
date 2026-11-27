/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.wear

import android.net.Uri
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
 * The file a watch pull asks for (v33): in a cold process — the state this class runs
 * the phone's stores in, first touch — [WatchAnswers.localFile] must find the file from
 * the store files alone (it inits and awaits both stores itself), and must still refuse
 * an address no answer exposes. Before the fix the same call read the stores before
 * their loads landed and answered null for a file the phone has: "error gone".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WatchLocalFileTest {

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
        File(app.filesDir, "local_playlists.json").writeText(root.toString())

        val opened = WatchAnswers.localFile(app, uri)
        assertNotNull("a listed file must open in a cold process", opened)
        assertEquals(4L, opened!!.second)
        assertEquals(listOf(1, 2, 3, 4), opened.first.readBytes().map { it.toInt() })

        assertNull(WatchAnswers.localFile(app, "file:///sdcard/Music/not-exposed.mp3"))
        assertNull(WatchAnswers.localFile(app, ""))
    }
}
