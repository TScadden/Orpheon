package app.myspoti.wear

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

/**
 * Whether the downloaded APK is really this app's update to [version], checked before the
 * installer is ever opened (the wear port of the phone's UpdatePackageVerifier, Scadify
 * v16): same package name, versionName equal to the release's version, and a signing
 * certificate equal to the installed app's. Android still performs the final install
 * verification; this rejects foreign APKs before they get that far. Scadify's builds —
 * phone and watch alike — all carry the one committed debug key (base/debug.keystore),
 * so a genuine watch release always passes. (The watch's minSdk is 30, so only the
 * signing-certificates path of the phone's version exists here.)
 */
internal fun trustedWearUpdate(context: Context, file: File, version: String): Boolean = runCatching {
    require(file.isFile && file.length() in 1..WearUpdatePolicy.MAX_APK_BYTES)
    val manager = context.packageManager
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val archive = manager.getPackageArchiveInfo(file.path, flags) ?: return false
    val installed = manager.getPackageInfo(context.packageName, flags)
    require(archive.packageName == context.packageName && archive.versionName == version)
    certificatesMatch(
        installed = signers(installed, history = false),
        incoming = signers(archive, history = false),
        incomingHistory = signers(archive, history = true),
    )
}.getOrDefault(false)

private fun signers(info: PackageInfo, history: Boolean): Set<String> {
    val signatures = info.signingInfo?.let {
        if (history && !it.hasMultipleSigners()) it.signingCertificateHistory else it.apkContentsSigners
    }
    return signatures.orEmpty().map { it.toCharsString() }.toSet()
}

/**
 * The certificate half of [trustedWearUpdate], as a pure function so it is unit-testable:
 * the signer sets are equal, or — a single-signer app whose key was rotated — the
 * incoming APK's certificate history contains the installed app's one certificate.
 */
internal fun certificatesMatch(installed: Set<String>, incoming: Set<String>, incomingHistory: Set<String>): Boolean =
    installed.isNotEmpty() && (installed == incoming || installed.size == 1 && incoming.size == 1 &&
        incomingHistory.containsAll(installed))
