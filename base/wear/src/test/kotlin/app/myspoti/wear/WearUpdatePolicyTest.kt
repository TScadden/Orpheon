package app.myspoti.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's update downloads are pinned to the watch's own releases repository —
 * and only it: the phone's feeds are foreign addresses here (Scadify v16).
 */
class WearUpdatePolicyTest {
    @Test fun theWatchRepoReleaseAssetsAreAllowed() {
        assertTrue(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk"))
        // Owner/repo and the extension compare without case.
        assertTrue(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/tscadden/SCADIFY-WEAR-RELEASES/releases/download/v16/Scadify-Watch-v16.APK"))
    }

    @Test fun thePhoneFeedsAndEverythingElseAreRejected() {
        // The phone's own repositories: another app for this updater, however genuine.
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-releases/releases/download/v16/scadify-v16.apk"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/Scadify/releases/download/v16/scadify-v16.apk"))
        // Another owner's repo, and another repo of the same owner.
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/SomeoneElse/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/tysons-island-releases/releases/download/v16/scadify-watch-v16.apk"))
        // Not HTTPS, a query or a fragment, credentials in the address, a non-default port.
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "http://github.com/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk?x=1"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk#x"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://user@github.com/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com:8080/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk"))
        // Not a release-asset path of the right shape, or not an APK.
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/nested/scadify-watch-v16.apk"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.zip"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl("not a url"))
        assertFalse(WearUpdatePolicy.isReleaseAssetUrl(""))
    }

    @Test fun downloadHopsAllowGithubAssetHostsOnly() {
        // The asset address itself is a valid first hop.
        assertTrue(WearUpdatePolicy.isDownloadHop(
            "https://github.com/TScadden/scadify-wear-releases/releases/download/v16/scadify-watch-v16.apk"))
        // GitHub's redirect targets for release assets.
        assertTrue(WearUpdatePolicy.isDownloadHop(
            "https://release-assets.githubusercontent.com/github-production-release-asset/123?sp=r&sv=2024"))
        assertTrue(WearUpdatePolicy.isDownloadHop("https://objects.githubusercontent.com/some/asset"))
        assertTrue(WearUpdatePolicy.isDownloadHop("https://github-releases.githubusercontent.com/some/asset"))
        // Anywhere else is not a hop of an update download — the phone's feed included.
        assertFalse(WearUpdatePolicy.isDownloadHop("https://example.com/scadify-watch-v16.apk"))
        assertFalse(WearUpdatePolicy.isDownloadHop(
            "https://github.com/TScadden/scadify-releases/releases/download/v16/scadify-v16.apk"))
        assertFalse(WearUpdatePolicy.isDownloadHop(
            "https://api.github.com/repos/TScadden/scadify-wear-releases/releases/latest"))
        assertFalse(WearUpdatePolicy.isDownloadHop(
            "http://release-assets.githubusercontent.com/insecure"))
    }
}
