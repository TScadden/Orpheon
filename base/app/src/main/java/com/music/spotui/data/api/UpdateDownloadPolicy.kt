package com.music.spotui.data.api

import java.net.URI

/**
 * Which addresses an in-app update may be downloaded from (ported from Tyson's Island's
 * update policy, Scadify's repos and size): only release assets of this project's own
 * GitHub repositories — the releases-only companion repo the checker reads, and the
 * source repo — plus GitHub's own asset hosts on the redirect hops between them.
 * Anything else is rejected before a byte is downloaded.
 */
internal object UpdateDownloadPolicy {
    /**
     * No release of this app comes near it; a bigger answer is not an APK of ours.
     * Island's cap is 100 MB; Scadify's APK is ~136 MB, so the cap is the 200 MB
     * [AppUpdater] already enforced.
     */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    private val repos = setOf("tscadden/scadify-releases", "tscadden/scadify")
    private val assetHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

    /** A release-asset download address of one of this project's own repositories. */
    fun isReleaseAssetUrl(value: String): Boolean = secureUri(value)?.let { uri ->
        val pieces = uri.path.orEmpty().trim('/').split('/')
        uri.host.equals("github.com", ignoreCase = true) && uri.fragment == null && uri.query == null &&
            pieces.size == 6 && "${pieces[0]}/${pieces[1]}".lowercase() in repos &&
            pieces[2] == "releases" && pieces[3] == "download" &&
            pieces[4].isNotBlank() && pieces[4] !in setOf(".", "..") && pieces[5].endsWith(".apk", ignoreCase = true)
    } == true

    /** One hop of a download: the asset address itself, or a GitHub asset host it redirects to. */
    fun isDownloadHop(value: String): Boolean = isReleaseAssetUrl(value) || secureUri(value)?.let {
        it.host.lowercase() in assetHosts && it.fragment == null
    } == true

    private fun secureUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.port in setOf(-1, 443)
    }
}
