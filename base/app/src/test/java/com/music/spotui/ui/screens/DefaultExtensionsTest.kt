package com.music.spotui.ui.screens

import app.myspoti.extensions.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup's default extensions: matched by name against the catalogues, newest listing wins. */
class DefaultExtensionsTest {

    private fun entry(name: String, versionCode: Int, version: String = "v$versionCode") = Catalog.Entry(
        id = "ext-$versionCode", name = name, version = version, versionCode = versionCode,
        author = "Someone", url = "pkg.zip", sha256 = "",
    )

    private fun catalog(vararg entries: Catalog.Entry) = Catalog(name = "Repo", extensions = entries.toList())

    @Test fun installedByNameWhateverTheCase() {
        val state = DefaultExtensions.state("Fresh Home", emptyList(), setOf("fresh home"), stillLooking = false)
        assertEquals(DefaultExtensions.State.Installed, state)
    }

    @Test fun theNewestListingAcrossCataloguesIsReady() {
        val catalogs = listOf(
            "https://example.com/a/index.json" to catalog(entry("Melzo", 100, "v1.0.0")),
            "https://example.com/b/index.json" to catalog(entry("Melzo", 105, "v1.0.5"), entry("Solid Source", 153, "v1.5.3")),
        )
        val melzo = DefaultExtensions.state("Melzo", catalogs, emptySet(), stillLooking = false)
        assertTrue(melzo is DefaultExtensions.State.Ready)
        melzo as DefaultExtensions.State.Ready
        assertEquals("https://example.com/b/index.json", melzo.repo)
        assertEquals("v1.0.5", melzo.entry.version)
        assertTrue(DefaultExtensions.state("Solid Source", catalogs, emptySet(), stillLooking = false) is DefaultExtensions.State.Ready)
    }

    @Test fun noMatchIsLookingThenUnavailable() {
        val catalogs = listOf("https://example.com/a/index.json" to catalog(entry("Other", 1)))
        assertEquals(DefaultExtensions.State.Looking, DefaultExtensions.state("Melzo", catalogs, emptySet(), stillLooking = true))
        assertEquals(DefaultExtensions.State.Unavailable, DefaultExtensions.state("Melzo", catalogs, emptySet(), stillLooking = false))
        // A name that merely resembles a default is not a match.
        val near = listOf("https://example.com/a/index.json" to catalog(entry("Melzo Lite", 1)))
        assertEquals(DefaultExtensions.State.Unavailable, DefaultExtensions.state("Melzo", near, emptySet(), stillLooking = false))
    }
}
