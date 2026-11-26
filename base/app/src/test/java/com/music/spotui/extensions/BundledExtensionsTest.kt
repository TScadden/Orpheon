package com.music.spotui.extensions

import app.myspoti.extensions.ExtensionPackage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bundled SoundCloud seed (v24): it runs only on a phone missing the extension whose
 * seed has not been settled, and the package in the assets is the released v1.0.1 — the
 * same bytes the store would hand over (its sha256 is the release's own).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BundledExtensionsTest {

    @Test fun theSeedRunsWhenTheExtensionIsMissingAndTheFlagIsUnset() {
        assertTrue(BundledExtensions.shouldSeed(installed = false, seeded = false))
    }

    @Test fun theSeedDoesNotRunWhenTheExtensionIsAlreadyInstalled() {
        assertFalse(BundledExtensions.shouldSeed(installed = true, seeded = false))
        assertFalse(BundledExtensions.shouldSeed(installed = true, seeded = true))
    }

    @Test fun theSeedDoesNotRunOnceSettled_evenAfterAnUninstall() {
        // The flag stays set when the extension goes (the user uninstalled it): the seed
        // must not bring it back.
        assertFalse(BundledExtensions.shouldSeed(installed = false, seeded = true))
    }

    @Test fun aBadBundledPackageIsAFinalFailure_otherFailuresAreNot() {
        assertTrue(BundledExtensions.isFinalFailure(BundledExtensions.BadBundledPackage("no parse")))
        assertFalse(BundledExtensions.isFinalFailure(java.io.IOException("a half-written registry")))
        assertFalse(BundledExtensions.isFinalFailure(IllegalStateException("blocked")))
    }

    /** The bundled bytes: through the app's assets where the runtime serves them, else the source file they are packaged from. */
    private fun bundledBytes(): ByteArray {
        runCatching {
            return RuntimeEnvironment.getApplication().assets.open(BundledExtensions.SOUNDCLOUD_ASSET).use { it.readBytes() }
        }
        val source = File("src/main/assets/${BundledExtensions.SOUNDCLOUD_ASSET}")
        if (source.isFile) return source.readBytes()
        fail("the bundled SoundCloud package is not in the assets")
        error("unreachable")
    }

    @Test fun theBundledPackageParsesAsTheSoundCloudExtension() {
        val pkg = ExtensionPackage.read(bundledBytes())
        assertEquals(BundledExtensions.SOUNDCLOUD_ID, pkg.manifest.id)
        assertEquals("1.0.1", pkg.manifest.version)
        assertEquals(2, pkg.manifest.versionCode)
        // The v1.0.1 release's own package, byte for byte (sha256 of the release asset).
        assertEquals("e262c5ad9cfbe5202202be3c0a33cc7d10123394a3b9918eb43c5b0495cf84d1", pkg.sha256)
        // What the seed grants is what the manifest asks for.
        assertEquals(
            setOf("audio.source", "download.source", "search.provide", "storage", "network"),
            Grants.keys(pkg.manifest),
        )
    }
}
