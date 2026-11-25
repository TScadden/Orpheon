package com.music.spotui.data.api

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Whether the downloaded APK is really this app's update to [version], checked before the
 * installer is ever opened (ported from Tyson's Island's verifier): same package name,
 * versionName equal to the release's version, and a signing certificate equal to the
 * installed app's. Android still performs the final install verification; this rejects
 * foreign APKs before they get that far. Scadify's builds all carry the one committed
 * debug key (base/debug.keystore), so a genuine release always passes.
 */
internal fun trustedUpdate(context: Context, file: File, version: String): Boolean = runCatching {
    require(file.isFile && file.length() in 1..UpdateDownloadPolicy.MAX_APK_BYTES)
    val manager = context.packageManager
    @Suppress("DEPRECATION")
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = manager.getPackageArchiveInfo(file.path, flags) ?: return false
    val installed = manager.getPackageInfo(context.packageName, flags)
    require(archive.packageName == context.packageName && archive.versionName == version)
    certificatesMatch(
        installed = signers(installed, history = false),
        incoming = signers(archive, history = false),
        incomingHistory = signers(archive, history = true),
    )
}.getOrDefault(false)

@Suppress("DEPRECATION")
private fun signers(info: PackageInfo, history: Boolean): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
        if (history && !it.hasMultipleSigners()) it.signingCertificateHistory else it.apkContentsSigners
    } else info.signatures
    return signatures.orEmpty().map { it.toCharsString() }.toSet()
}

/**
 * The certificate half of [trustedUpdate], as a pure function so it is unit-testable:
 * the signer sets are equal, or — a single-signer app whose key was rotated — the
 * incoming APK's certificate history contains the installed app's one certificate.
 */
internal fun certificatesMatch(installed: Set<String>, incoming: Set<String>, incomingHistory: Set<String>): Boolean =
    installed.isNotEmpty() && (installed == incoming || installed.size == 1 && incoming.size == 1 &&
        incomingHistory.containsAll(installed))
