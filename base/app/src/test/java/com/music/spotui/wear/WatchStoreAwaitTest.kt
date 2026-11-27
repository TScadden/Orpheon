/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.wear

import com.music.spotui.data.local.LocalItem
import com.music.spotui.data.local.LocalLikes
import com.music.spotui.data.local.LocalPlaylists
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The watch answers' cold-process race (v33): the local stores load their files on
 * their own thread after init, and the watch answers read them at once — in a cold
 * process (a watch pull is often what wakes the phone app) the exposed set read empty
 * or partial, and a file the phone has was answered "gone", or Liked Songs came back
 * short. awaitLoaded is the contract the answers now rely on: when it returns, the
 * file's content IS the store's state. Each store is touched cold exactly once here —
 * its first touch in this class is the fixture's.
 *
 * The qualifiers in the config are this class's own on purpose: Robolectric classes
 * sharing a config share a sandbox, and with it the stores' singletons — an earlier
 * class (OnboardingTest) inits them against its own data dir and loads them empty,
 * which would leave these tests reading a store that can never see the fixture. A
 * config of its own is a cold process of its own, the state under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class WatchStoreAwaitTest {

    @Test fun likedFilesAreThereWhenTheAwaitReturns() {
        val app = RuntimeEnvironment.getApplication()
        val item = LocalItem(
            key = "k1", uri = "content://media/external/audio/media/42", title = "Liked Song",
            artist = "Artist", album = "Album", durationMs = 123_000L, artUri = "",
        )
        File(app.filesDir, "local_likes.json").writeText(LocalLikes.encode(listOf(item)))
        LocalLikes.init(app)
        LocalLikes.awaitLoaded()
        assertEquals(listOf(item), LocalLikes.items.toList())
    }

    @Test fun playlistFilesAreThereWhenTheAwaitReturns() {
        val app = RuntimeEnvironment.getApplication()
        val item = JSONObject().put("key", "k1").put("uri", "content://media/external/audio/media/7")
            .put("title", "Listed Song").put("artist", "Artist").put("album", "Album")
            .put("duration", 111_000).put("art", "")
        val list = JSONObject().put("id", "lp1").put("name", "Road Mix").put("created", 1)
            .put("cover", "").put("items", JSONArray().put(item))
        val root = JSONObject().put("version", 1).put("playlists", JSONArray().put(list))
            .put("additions", JSONObject())
        File(app.filesDir, "local_playlists.json").writeText(root.toString())
        LocalPlaylists.init(app)
        LocalPlaylists.awaitLoaded()
        val p = LocalPlaylists.playlists.getValue("lp1")
        assertEquals("Road Mix", p.name)
        assertEquals("Listed Song", p.items.single().title)
    }
}
