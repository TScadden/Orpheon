package app.myspoti.wear

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The watch's update checker (Scadify v16): version comparison, and which releases of
 * the watch's own feed become offers. Robolectric (SDK 35, as PhoneModeTest) for a
 * working org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearUpdateCheckerTest {
    /** Scadify's own numbering is plain numbers: "16" is the build after "15". */
    @Test fun plainNumberVersionsCompareNumerically() {
        assertTrue(WearUpdateChecker.isNewer("16", "15"))
        assertTrue(WearUpdateChecker.isNewer("17", "16"))
        assertFalse(WearUpdateChecker.isNewer("16", "16"))
        assertFalse(WearUpdateChecker.isNewer("9", "16"))
        assertFalse(WearUpdateChecker.isNewer("15", "16"))
        assertTrue(WearUpdateChecker.isNewer("1.10.0", "1.9.2"))
    }

    /** Non-numeric junk counts as 0: it is never newer than a real version. */
    @Test fun junkVersionsAreNeverNewer() {
        assertFalse(WearUpdateChecker.isNewer("junk", "16"))
        assertFalse(WearUpdateChecker.isNewer("junk", "junk"))
        assertFalse(WearUpdateChecker.isNewer("", "1"))
        assertTrue(WearUpdateChecker.isNewer("17", "junk"))
    }

    @Test fun onlyAVersionTagIsARelease() {
        assertTrue(WearUpdateChecker.isVersionTag("v16"))
        assertTrue(WearUpdateChecker.isVersionTag("16"))
        assertTrue(WearUpdateChecker.isVersionTag("v2.4.0"))
        assertTrue(WearUpdateChecker.isVersionTag("2.5.0-beta.1"))
        assertFalse(WearUpdateChecker.isVersionTag("automix2-build205"))
        assertFalse(WearUpdateChecker.isVersionTag(""))
    }

    private fun asset(name: String, url: String, size: Long = 1000) =
        JSONObject().put("name", name).put("browser_download_url", url).put("size", size)

    private fun release(tag: String, vararg assets: JSONObject, draft: Boolean = false, prerelease: Boolean = false) =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("html_url", "https://github.com/TScadden/scadify-wear-releases/releases/tag/$tag").put("body", "- Something new")
            .put("assets", JSONArray(assets.toList()))

    private val apkUrl = "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk"

    /** Island-style selection: the first asset that is an APK, is not a debug build, is within the size cap and sits at a trusted address. */
    @Test fun picksTheFirstRealApkAsset() {
        val r = release("v16",
            asset("notes.txt", "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/notes.txt"),
            asset("wear-debug.apk", "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/wear-debug.apk"),
            asset("scadify-watch-v16.apk", apkUrl, size = 94_000_000),
        )
        val picked = WearUpdateChecker.apkAsset(r)
        assertEquals(apkUrl, picked?.url)
        assertEquals(94_000_000L, picked?.sizeBytes)
        // An uppercase extension is still an APK.
        val upper = release("v16", asset("Scadify-Watch-v16.APK", apkUrl))
        assertEquals(apkUrl, WearUpdateChecker.apkAsset(upper)?.url)
    }

    @Test fun noUsableAssetMeansNoLink() {
        // Only a debug APK: not offered.
        assertNull(WearUpdateChecker.apkAsset(release("v16",
            asset("wear-debug.apk", "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/wear-debug.apk"))))
        // The PHONE's APK at the phone's feed: a genuine Scadify address, and still not
        // an offer — this updater only ever takes the watch's own feed.
        assertNull(WearUpdateChecker.apkAsset(release("v16",
            asset("scadify-v16.apk", "https://github.com/TScadden/scadify-releases/releases/download/v16/scadify-v16.apk"))))
        // An APK at an address the download policy does not trust: not offered.
        assertNull(WearUpdateChecker.apkAsset(release("v16",
            asset("scadify-watch-v16.apk", "https://github.com/SomeoneElse/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk"))))
        assertNull(WearUpdateChecker.apkAsset(release("v16",
            asset("scadify-watch-v16.apk", "https://example.com/scadify-watch-v16.apk"))))
        // Over the watch's size cap: not offered.
        assertNull(WearUpdateChecker.apkAsset(release("v16",
            asset("scadify-watch-v16.apk", apkUrl, size = WearUpdatePolicy.MAX_APK_BYTES + 1))))
        // No assets at all.
        assertNull(WearUpdateChecker.apkAsset(release("v16")))
        assertNull(WearUpdateChecker.apkAsset(JSONObject("{}")))
    }

    @Test fun aReleaseBecomesAnOfferOnlyWhenItIsThisApps() {
        val good = release("v16", asset("scadify-watch-v16.apk", apkUrl, size = 94_000_000))
        val offer = WearUpdateChecker.releaseFrom(good, beta = false)
        assertEquals("16", offer?.version)
        assertEquals(apkUrl, offer?.apkUrl)
        assertEquals(94_000_000L, offer?.sizeBytes)
        assertEquals("• Something new", offer?.notes)
        // Drafts and the other channel are not offers.
        assertNull(WearUpdateChecker.releaseFrom(release("v16", asset("scadify-watch-v16.apk", apkUrl), draft = true), beta = false))
        assertNull(WearUpdateChecker.releaseFrom(release("v16", asset("scadify-watch-v16.apk", apkUrl), prerelease = true), beta = false))
        assertEquals("16", WearUpdateChecker.releaseFrom(release("v16", asset("scadify-watch-v16.apk", apkUrl), prerelease = true), beta = true)?.version)
        // A tag that is not a version, and a release without a usable APK, are not offers.
        assertNull(WearUpdateChecker.releaseFrom(release("automix2-build205", asset("scadify-watch-v16.apk", apkUrl)), beta = false))
        assertNull(WearUpdateChecker.releaseFrom(release("v16"), beta = false))
    }
}
