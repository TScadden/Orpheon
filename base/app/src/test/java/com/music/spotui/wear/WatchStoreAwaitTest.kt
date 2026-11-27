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
 * file's content IS the store's state.
 *
 * Robolectric classes sharing a config share a sandbox, and with it the stores'
 * singletons: an earlier class (OnboardingTest) inits them against its own data dir
 * and loads them empty, and a store init'd once can never be init'd again. So each
 * test first puts its store back to the state a cold process starts in — the file
 * this test wrote, nothing loaded, nothing in memory — and only then walks the
 * production path (init, then the await).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WatchStoreAwaitTest {

    /** [store] back to a cold process's start, its file the one at [file] (see the class doc). */
    private fun cold(store: Any, file: File) {
        val cls = store.javaClass
        cls.getDeclaredField("file").apply { isAccessible = true }.set(store, file)
        cls.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(store, false)
    }

    @Test fun likedFilesAreThereWhenTheAwaitReturns() {
        val app = RuntimeEnvironment.getApplication()
        val item = LocalItem(
            key = "k1", uri = "content://media/external/audio/media/42", title = "Liked Song",
            artist = "Artist", album = "Album", durationMs = 123_000L, artUri = "",
        )
        val file = File(app.filesDir, "local_likes.json")
        file.writeText(LocalLikes.encode(listOf(item)))
        cold(LocalLikes, file)
        LocalLikes.items.clear()
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
        val file = File(app.filesDir, "local_playlists.json")
        file.writeText(root.toString())
        cold(LocalPlaylists, file)
        LocalPlaylists.playlists.clear()
        LocalPlaylists.init(app)
        LocalPlaylists.awaitLoaded()
        val p = LocalPlaylists.playlists.getValue("lp1")
        assertEquals("Road Mix", p.name)
        assertEquals("Listed Song", p.items.single().title)
    }
}
