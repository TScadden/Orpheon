package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun comparesNumericallyNotAsText() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"))
        assertTrue(UpdateChecker.isNewer("0.9.1", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
    }

    /** Scadify's own numbering is plain numbers: "14" is the build after "13". */
    @Test fun plainNumberVersionsCompareNumerically() {
        assertTrue(UpdateChecker.isNewer("14", "13"))
        assertTrue(UpdateChecker.isNewer("15", "14"))
        assertFalse(UpdateChecker.isNewer("14", "14"))
        assertFalse(UpdateChecker.isNewer("9", "14"))
        assertFalse(UpdateChecker.isNewer("13", "14"))
    }

    /** Non-numeric junk counts as 0: it is never newer than a real version. */
    @Test fun junkVersionsAreNeverNewer() {
        assertFalse(UpdateChecker.isNewer("junk", "14"))
        assertFalse(UpdateChecker.isNewer("junk", "junk"))
        assertFalse(UpdateChecker.isNewer("", "1"))
        assertTrue(UpdateChecker.isNewer("15", "junk"))
    }

    @Test fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.8.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0-beta", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "2.0.0-beta.3"))
        assertTrue(UpdateChecker.isNewer("2.0.0-beta.2", "2.0.0-beta.1"))
        assertFalse(UpdateChecker.isNewer("2.0.0-beta.1", "2.0.0-beta.1"))
        assertTrue(UpdateChecker.isNewer("2.0.1-beta.1", "2.0.0"))
    }
    @Test fun ordersBetasAndReleases() {
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.7", "2.0.2-beta.6"))
        assertFalse(UpdateChecker.isNewer("2.0.2-beta.6", "2.0.2-beta.7"))
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.10", "2.0.2-beta.9"))
        assertTrue(UpdateChecker.isNewer("2.0.2", "2.0.2-beta.7"))
        assertFalse(UpdateChecker.isNewer("2.0.2-beta.7", "2.0.2"))
        assertTrue(UpdateChecker.isNewer("2.0.3-beta.1", "2.0.2"))
        assertTrue(UpdateChecker.isNewer("2.1.0-beta.1", "2.0.9"))
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.1", "2.0.1-beta.9"))
    }
    @Test fun notesKeepTheChangelogSectionAsPlainLines() {
        val body = "- **Local Files**: a folder\n  - nested [link](https://x.y)\n\n**Full Changelog**: https://github.com/a/b/compare/v1...v2"
        assertEquals("• Local Files: a folder\n  • nested link", UpdateChecker.cleanNotes(body))
    }
    @Test fun onlyAVersionTagIsARelease() {
        assertTrue(UpdateChecker.isVersionTag("v14"))
        assertTrue(UpdateChecker.isVersionTag("14"))
        assertTrue(UpdateChecker.isVersionTag("v2.4.0"))
        assertTrue(UpdateChecker.isVersionTag("2.5.0-beta.1"))
        assertFalse(UpdateChecker.isVersionTag("automix2-build205"))
        assertFalse(UpdateChecker.isVersionTag("mixlab-3150-private"))
        assertFalse(UpdateChecker.isVersionTag(""))
    }

    private fun asset(name: String, url: String, size: Long = 1000) =
        org.json.JSONObject().put("name", name).put("browser_download_url", url).put("size", size)

    private fun release(tag: String, vararg assets: org.json.JSONObject, draft: Boolean = false, prerelease: Boolean = false) =
        org.json.JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("html_url", "https://github.com/TScadden/scadify-releases/releases/tag/$tag").put("body", "- Something new")
            .put("assets", org.json.JSONArray(assets.toList()))

    private val apkUrl = "https://github.com/TScadden/scadify-releases/releases/download/v14/scadify-v14.apk"

    /** Island-style selection: the first asset that is an APK, is not a debug build, is within the size cap and sits at a trusted address. */
    @Test fun picksTheFirstRealApkAsset() {
        val r = release("v14",
            asset("notes.txt", "https://github.com/TScadden/scadify-releases/releases/download/v14/notes.txt"),
            asset("app-arm64-v8a-debug.apk", "https://github.com/TScadden/scadify-releases/releases/download/v14/app-arm64-v8a-debug.apk"),
            asset("scadify-v14.apk", apkUrl, size = 136_000_000),
        )
        val picked = UpdateChecker.apkAsset(r)
        assertEquals(apkUrl, picked?.url)
        assertEquals(136_000_000L, picked?.sizeBytes)
        // An uppercase extension is still an APK.
        val upper = release("v14", asset("Scadify-v14.APK", apkUrl))
        assertEquals(apkUrl, UpdateChecker.apkAsset(upper)?.url)
    }

    @Test fun noUsableAssetMeansNoLink() {
        // Only a debug APK: not offered.
        assertNull(UpdateChecker.apkAsset(release("v14",
            asset("app-arm64-v8a-debug.apk", "https://github.com/TScadden/scadify-releases/releases/download/v14/app-arm64-v8a-debug.apk"))))
        // An APK at an address the download policy does not trust: not offered.
        assertNull(UpdateChecker.apkAsset(release("v14",
            asset("scadify-v14.apk", "https://github.com/SomeoneElse/scadify-releases/releases/download/v14/scadify-v14.apk"))))
        assertNull(UpdateChecker.apkAsset(release("v14",
            asset("scadify-v14.apk", "https://example.com/scadify-v14.apk"))))
        // Over the size cap: not offered.
        assertNull(UpdateChecker.apkAsset(release("v14",
            asset("scadify-v14.apk", apkUrl, size = UpdateDownloadPolicy.MAX_APK_BYTES + 1))))
        // No assets at all.
        assertNull(UpdateChecker.apkAsset(release("v14")))
        assertNull(UpdateChecker.apkAsset(org.json.JSONObject("{}")))
    }

    @Test fun aReleaseBecomesAnOfferOnlyWhenItIsThisApps() {
        val good = release("v14", asset("scadify-v14.apk", apkUrl, size = 136_000_000))
        val offer = UpdateChecker.releaseFrom(good, beta = false)
        assertEquals("14", offer?.version)
        assertEquals(apkUrl, offer?.apkUrl)
        assertEquals(136_000_000L, offer?.sizeBytes)
        assertEquals("• Something new", offer?.notes)
        // Drafts and the other channel are not offers.
        assertNull(UpdateChecker.releaseFrom(release("v14", asset("scadify-v14.apk", apkUrl), draft = true), beta = false))
        assertNull(UpdateChecker.releaseFrom(release("v14", asset("scadify-v14.apk", apkUrl), prerelease = true), beta = false))
        assertEquals("14", UpdateChecker.releaseFrom(release("v14", asset("scadify-v14.apk", apkUrl), prerelease = true), beta = true)?.version)
        // A tag that is not a version, and a release without a usable APK, are not offers.
        assertNull(UpdateChecker.releaseFrom(release("automix2-build205", asset("scadify-v14.apk", apkUrl)), beta = false))
        assertNull(UpdateChecker.releaseFrom(release("v14"), beta = false))
    }
}
