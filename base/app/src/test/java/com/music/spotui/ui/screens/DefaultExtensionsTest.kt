package com.music.spotui.ui.screens

import app.myspoti.extensions.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup's default extensions: the installed registry is matched first, by manifest id
 * (the name only as a fallback), and an installed extension with a newer listing is an
 * update — never a fresh install, and never "not in the stores" (both reported from a phone
 * whose registry held all three while the step said otherwise).
 */
class DefaultExtensionsTest {

    private fun entry(id: String, name: String, versionCode: Int, version: String = "v$versionCode") = Catalog.Entry(
        id = id, name = name, version = version, versionCode = versionCode,
        author = "Someone", url = "pkg.zip", sha256 = "",
    )

    private fun installed(id: String, name: String, version: String, versionCode: Int) =
        DefaultExtensions.Installed(id = id, name = name, version = version, versionCode = versionCode)

    private fun catalog(vararg entries: Catalog.Entry) = Catalog(name = "Repo", extensions = entries.toList())

    private val repo = "https://example.com/index.json"

    @Test fun installedByIdWhateverItsPackageCallsItself() {
        // The registry keeps the name the package installed under; the catalogue's name is
        // its own metadata. Identity is the manifest id both carry.
        val catalogs = listOf(repo to catalog(entry("io.fresh", "Fresh Home", 2, "1.1.0")))
        val onPhone = listOf(installed("io.fresh", "Fills Home", "1.1.0", 2))
        assertEquals(DefaultExtensions.State.Installed, DefaultExtensions.state("Fresh Home", catalogs, onPhone, stillLooking = false))
    }

    @Test fun installedByNameWhateverTheCase() {
        val onPhone = listOf(installed("io.fresh", "fresh home", "1.0.0", 1))
        assertEquals(DefaultExtensions.State.Installed, DefaultExtensions.state("Fresh Home", emptyList(), onPhone, stillLooking = false))
    }

    @Test fun installedWithANewerListingIsAnUpdateNotAnInstall() {
        val catalogs = listOf(repo to catalog(entry("io.fresh", "Fresh Home", 2, "1.1.0")))
        val onPhone = listOf(installed("io.fresh", "Fresh Home", "1.0.0", 1))
        val state = DefaultExtensions.state("Fresh Home", catalogs, onPhone, stillLooking = false)
        assertTrue(state is DefaultExtensions.State.Update)
        state as DefaultExtensions.State.Update
        assertEquals(repo, state.repo)
        assertEquals("1.1.0", state.entry.version)
        assertEquals("1.0.0", state.installedVersion)
    }

    @Test fun installedAndCurrentWhenTheListingIsTheSameOrOlder() {
        val onPhone = listOf(installed("io.solid", "Solid Source", "1.5.3", 153))
        val same = listOf(repo to catalog(entry("io.solid", "Solid Source", 153, "1.5.3")))
        assertEquals(DefaultExtensions.State.Installed, DefaultExtensions.state("Solid Source", same, onPhone, stillLooking = false))
        val older = listOf(repo to catalog(entry("io.solid", "Solid Source", 150, "1.5.0")))
        assertEquals(DefaultExtensions.State.Installed, DefaultExtensions.state("Solid Source", older, onPhone, stillLooking = false))
    }

    @Test fun aNamesakeUnderAnotherIdIsNotAnUpdate() {
        // Same display name, different manifest id: another package, not a newer version of
        // the one on the phone — the default is on the phone, so it reads as installed.
        val catalogs = listOf(repo to catalog(entry("io.other", "Fresh Home", 99, "9.9.9")))
        val onPhone = listOf(installed("io.fresh", "Fresh Home", "1.0.0", 1))
        assertEquals(DefaultExtensions.State.Installed, DefaultExtensions.state("Fresh Home", catalogs, onPhone, stillLooking = false))
    }

    @Test fun installedButInNoCatalogueIsInstalledNotUnavailable() {
        // Melzo on Tyson's phone: installed and running, its repository's index not among
        // the catalogues the step read. The registry is the authority for what is installed.
        val onPhone = listOf(installed("io.melzo", "Melzo", "1.0.5", 105))
        assertEquals(DefaultExtensions.State.Installed, DefaultExtensions.state("Melzo", emptyList(), onPhone, stillLooking = false))
    }

    @Test fun theNewestListingAcrossCataloguesIsReady() {
        val catalogs = listOf(
            "https://example.com/a/index.json" to catalog(entry("io.melzo", "Melzo", 100, "v1.0.0")),
            "https://example.com/b/index.json" to catalog(entry("io.melzo", "Melzo", 105, "v1.0.5"), entry("io.solid", "Solid Source", 153, "v1.5.3")),
        )
        val melzo = DefaultExtensions.state("Melzo", catalogs, emptyList(), stillLooking = false)
        assertTrue(melzo is DefaultExtensions.State.Ready)
        melzo as DefaultExtensions.State.Ready
        assertEquals("https://example.com/b/index.json", melzo.repo)
        assertEquals("v1.0.5", melzo.entry.version)
        assertTrue(DefaultExtensions.state("Solid Source", catalogs, emptyList(), stillLooking = false) is DefaultExtensions.State.Ready)
    }

    @Test fun anUpdateWhosePackageIsNotNewerSettlesToInstalled() {
        // Fresh Home's dead end: the listing advertises 1.1.0, the package it serves is
        // the 1.0.0 already on the phone, so the install plan refuses as not newer. The
        // step then calls the entry installed instead of offering the update forever.
        val update = DefaultExtensions.State.Update(repo, entry("io.fresh", "Fresh Home", 2, "1.1.0"), "1.0.0")
        assertEquals(DefaultExtensions.State.Installed, DefaultExtensions.settled(update, planNotNewer = true))
        // A package that really is newer leaves the update standing.
        assertEquals(update, DefaultExtensions.settled(update, planNotNewer = false))
        // Nothing else settles: a fresh install offered a not-newer plan cannot happen
        // (nothing is installed), and the other states pass through either way.
        val ready = DefaultExtensions.State.Ready(repo, entry("io.melzo", "Melzo", 105))
        assertEquals(ready, DefaultExtensions.settled(ready, planNotNewer = true))
        assertEquals(DefaultExtensions.State.Installed, DefaultExtensions.settled(DefaultExtensions.State.Installed, planNotNewer = true))
        assertEquals(DefaultExtensions.State.Unavailable, DefaultExtensions.settled(DefaultExtensions.State.Unavailable, planNotNewer = true))
    }

    @Test fun noMatchIsLookingThenUnavailable() {
        val catalogs = listOf(repo to catalog(entry("io.other", "Other", 1)))
        assertEquals(DefaultExtensions.State.Looking, DefaultExtensions.state("Melzo", catalogs, emptyList(), stillLooking = true))
        assertEquals(DefaultExtensions.State.Unavailable, DefaultExtensions.state("Melzo", catalogs, emptyList(), stillLooking = false))
        // A name that merely resembles a default is not a match — in a catalogue, or on the phone.
        val near = listOf(repo to catalog(entry("io.melzo-lite", "Melzo Lite", 1)))
        assertEquals(DefaultExtensions.State.Unavailable, DefaultExtensions.state("Melzo", near, emptyList(), stillLooking = false))
        val nearOnPhone = listOf(installed("io.melzo-lite", "Melzo Lite", "1.0.0", 1))
        assertEquals(DefaultExtensions.State.Unavailable, DefaultExtensions.state("Melzo", emptyList(), nearOnPhone, stillLooking = false))
    }
}
