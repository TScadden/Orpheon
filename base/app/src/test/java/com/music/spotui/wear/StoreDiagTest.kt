/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.wear

import com.music.spotui.data.local.LocalItem
import com.music.spotui.data.local.LocalLikes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** TEMPORARY diagnostic for the v33 store-await failures: prints the store's internals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StoreDiagTest {

    private fun field(name: String): Any? {
        val f = LocalLikes::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(LocalLikes)
    }

    @Test fun diag() {
        val app = RuntimeEnvironment.getApplication()
        System.err.println("DIAG filesDir=${app.filesDir} exists=${app.filesDir.exists()}")
        System.err.println("DIAG before: fileInit=${runCatching { field("file") }.isSuccess} loaded=${field("loaded")} items=${LocalLikes.items.size}")
        val item = LocalItem("k1", "content://media/42", "Liked Song", "Artist", "Album", 123_000L, "")
        val fixture = File(app.filesDir, "local_likes.json")
        fixture.writeText(LocalLikes.encode(listOf(item)))
        System.err.println("DIAG fixture=${fixture.absolutePath} exists=${fixture.exists()} len=${fixture.length()} text=${fixture.readText().take(120)}")
        LocalLikes.init(app)
        System.err.println("DIAG afterInit: file=${field("file")} loaded=${field("loaded")} items=${LocalLikes.items.size}")
        LocalLikes.awaitLoaded()
        System.err.println("DIAG afterAwait: loaded=${field("loaded")} items=${LocalLikes.items.size}")
        val storeFile = field("file") as File
        System.err.println("DIAG storeFile exists=${storeFile.exists()} samePath=${storeFile.absolutePath == fixture.absolutePath}")
        System.err.println("DIAG directRead=${runCatching { LocalLikes.decode(storeFile.readText()) }.getOrElse { "THREW $it" }}")
    }
}
