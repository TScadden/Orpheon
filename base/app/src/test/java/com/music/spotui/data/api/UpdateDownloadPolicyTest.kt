package com.music.spotui.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Update downloads are pinned to this project's own GitHub repositories. */
class UpdateDownloadPolicyTest {
    @Test fun ownReposReleaseAssetsAreAllowed() {
        assertTrue(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-releases/releases/download/v14/scadify-v14.apk"))
        assertTrue(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/Scadify/releases/download/v14/scadify-v14.apk"))
        // Owner/repo and the extension compare without case.
        assertTrue(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/tscadden/SCADIFY-RELEASES/releases/download/v14/Scadify-v14.APK"))
    }

    @Test fun foreignOrMalformedAddressesAreRejected() {
        // Another owner's repo, and another repo of the same owner.
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/SomeoneElse/scadify-releases/releases/download/v14/scadify-v14.apk"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/tysons-island-releases/releases/download/v14/scadify-v14.apk"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/kota-zo/Orpheon/releases/download/v14/scadify-v14.apk"))
        // Not HTTPS, a query or a fragment, credentials in the address, a non-default port.
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "http://github.com/TScadden/scadify-releases/releases/download/v14/scadify-v14.apk"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-releases/releases/download/v14/scadify-v14.apk?x=1"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-releases/releases/download/v14/scadify-v14.apk#x"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://user@github.com/TScadden/scadify-releases/releases/download/v14/scadify-v14.apk"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com:8080/TScadden/scadify-releases/releases/download/v14/scadify-v14.apk"))
        // Not a release-asset path of the right shape, or not an APK.
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-releases/releases/download/v14/nested/scadify-v14.apk"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(
            "https://github.com/TScadden/scadify-releases/releases/download/v14/scadify-v14.zip"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl("not a url"))
        assertFalse(UpdateDownloadPolicy.isReleaseAssetUrl(""))
    }

    @Test fun downloadHopsAllowGithubAssetHostsOnly() {
        // The asset address itself is a valid first hop.
        assertTrue(UpdateDownloadPolicy.isDownloadHop(
            "https://github.com/TScadden/scadify-releases/releases/download/v14/scadify-v14.apk"))
        // GitHub's redirect targets for release assets.
        assertTrue(UpdateDownloadPolicy.isDownloadHop(
            "https://release-assets.githubusercontent.com/github-production-release-asset/123?sp=r&sv=2024"))
        assertTrue(UpdateDownloadPolicy.isDownloadHop("https://objects.githubusercontent.com/some/asset"))
        assertTrue(UpdateDownloadPolicy.isDownloadHop("https://github-releases.githubusercontent.com/some/asset"))
        // Anywhere else is not a hop of an update download.
        assertFalse(UpdateDownloadPolicy.isDownloadHop("https://example.com/scadify-v14.apk"))
        assertFalse(UpdateDownloadPolicy.isDownloadHop("https://api.github.com/repos/TScadden/scadify-releases/releases/latest"))
        assertFalse(UpdateDownloadPolicy.isDownloadHop(
            "http://release-assets.githubusercontent.com/insecure"))
    }
}
