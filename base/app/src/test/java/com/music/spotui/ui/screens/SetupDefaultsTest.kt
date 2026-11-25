/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.myspoti.engine.R
import app.myspoti.extensions.Catalog
import app.myspoti.extensions.Discovery
import com.music.spotui.data.preferences.Look
import com.music.spotui.data.preferences.LookPref
import com.music.spotui.di.Graph
import com.music.spotui.extensions.ExtensionStore
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.InstalledExtension
import com.music.spotui.extensions.Registry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The setup's "Your extensions" step, as it runs on a phone. The store's catalogues are
 * seeded straight into the store (every one of them already read, so the step's refresh
 * fetches nothing), and the one repository offered points at an address that refuses at
 * once: tapping Install must run the sequence and say the failure on the step. The step
 * that shipped in v3 drove the sequence from an effect keyed on the queue it consumed —
 * the tap cancelled the download inside itself and the failure handler swallowed the
 * cancellation, so the button did nothing at all, silently (reported from Tyson's phone,
 * all three defaults already installed and still misread: see the second test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupDefaultsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()

    /** An index nothing answers at: the connection is refused immediately, everywhere. */
    private val deadRepo = "https://127.0.0.1:1/index.json"

    private fun entry(id: String, name: String, version: String, versionCode: Int) = Catalog.Entry(
        id = id, name = name, version = version, versionCode = versionCode,
        author = "Someone", url = "pkg.zip", sha256 = "",
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> flowOf(target: Any, field: String): MutableStateFlow<T> =
        target::class.java.getDeclaredField(field).apply { isAccessible = true }.get(target) as MutableStateFlow<T>

    /** The store as if every catalogue had been read: the step's refresh then changes nothing. */
    private fun seedStore(catalogs: Map<String, Catalog>) {
        val repos = catalogs.mapValues { (url, catalog) -> ExtensionStore.Repo(url, catalog = catalog) }.toMutableMap()
        repos.getOrPut(ExtensionStore.OFFICIAL_REPO) { ExtensionStore.Repo(ExtensionStore.OFFICIAL_REPO, catalog = Catalog("Official", emptyList())) }
        repos.getOrPut(Discovery.SOURCE) { ExtensionStore.Repo(Discovery.SOURCE, catalog = Catalog("Community", emptyList())) }
        flowOf<Map<String, ExtensionStore.Repo>>(ExtensionStore, "_repos").value = repos
        ExtensionStore.busy.value = emptySet()
        pending.value = null
    }

    private fun seedRegistry(registry: Registry) {
        flowOf<Registry>(Extensions, "_registry").value = registry
    }

    @Before fun setUp() {
        Graph::class.java.getDeclaredField("app").apply { isAccessible = true }.set(null, app)
        app.getSharedPreferences("myspoti_prefs", 0).edit().clear().commit()
        app.getSharedPreferences("settings_prefs", 0).edit().clear().commit()
        LookPref.clearOffered(app)
    }

    @After fun tearDown() {
        LookPref.set(app, Look.CLASSIC)
        seedRegistry(Registry())
        seedStore(emptyMap())
    }

    @Test fun `tapping install runs the sequence and a refused download is said on the step`() {
        seedRegistry(Registry(repositories = listOf(deadRepo)))
        seedStore(mapOf(deadRepo to Catalog("Test", listOf(entry("io.fresh", "Fresh Home", "1.1.0", 2)))))

        rule.setContent { DefaultsStep(onNext = {}) }

        rule.onNodeWithText(app.getString(R.string.setup_defaults_ready, "1.1.0")).assertExists()
        rule.onNodeWithText(app.getString(R.string.setup_defaults_install)).performScrollTo().performClick()

        // The download is refused at once; the step must say so, and the sequence must end
        // (the Install button back), not hang on its progress or swallow the failure.
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Couldn't fetch Fresh Home", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(app.getString(R.string.setup_defaults_install)).assertExists()
    }

    @Test fun `installed defaults read as installed, an older one as an update`() {
        seedRegistry(
            Registry(
                extensions = listOf(
                    InstalledExtension(id = "io.fresh", name = "Fresh Home", version = "1.0.0", versionCode = 1, author = "Someone", sha256 = "x", granted = emptySet()),
                    InstalledExtension(id = "io.melzo", name = "Melzo", version = "1.0.5", versionCode = 105, author = "Someone", sha256 = "x", granted = emptySet()),
                ),
                repositories = listOf(deadRepo),
            ),
        )
        // Only Fresh Home is in a catalogue the phone can see, at a newer version. Melzo is
        // in none — installed and running, so it reads as installed all the same; Solid
        // Source is nowhere, and reads as unavailable.
        seedStore(mapOf(deadRepo to Catalog("Test", listOf(entry("io.fresh", "Fresh Home", "1.1.0", 2)))))

        rule.setContent { DefaultsStep(onNext = {}) }

        rule.onNodeWithText(app.getString(R.string.setup_defaults_update, "1.1.0", "1.0.0")).assertExists()
        rule.onNodeWithText(app.getString(R.string.setup_defaults_installed)).assertExists()
        assertEquals(
            1,
            rule.onAllNodesWithText(app.getString(R.string.setup_defaults_unavailable)).fetchSemanticsNodes().size,
        )
    }
}
