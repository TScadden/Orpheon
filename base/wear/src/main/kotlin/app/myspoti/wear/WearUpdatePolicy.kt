package app.myspoti.wear

import java.net.URI

/**
 * Which addresses the watch app's update may be downloaded from (the wear port of the
 * phone's UpdateDownloadPolicy, Scadify v16): only release assets of the watch's OWN
 * releases repository — TScadden/scadify-wear-releases — plus GitHub's own asset hosts
 * on the redirect hops between them. Anything else is rejected before a byte is
 * downloaded.
 *
 * The pin is deliberately narrower than the phone's: the phone's repositories
 * (scadify-releases, Scadify) are NOT trusted here. The phone and watch apps share a
 * package name, a version numbering and a signing key, so each updater must only ever
 * see its own feed — a phone APK offered to the watch (or the other way round) would
 * pass every other check and install the wrong app.
 */
internal object WearUpdatePolicy {
    /**
     * No watch release comes near it; a bigger answer is not an APK of ours. The watch
     * APK is ~94 MB (the phone's is ~136 MB, its cap 200 MB), so the cap is 150 MB:
     * headroom for growth, still far under anything that is not this app.
     */
    const val MAX_APK_BYTES = 150L * 1024 * 1024

    private val repos = setOf("tscadden/scadify-wear-releases")
    private val assetHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

    /** A release-asset download address of the watch's own releases repository. */
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
