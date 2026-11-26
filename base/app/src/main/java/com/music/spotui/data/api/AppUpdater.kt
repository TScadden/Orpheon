package com.music.spotui.data.api

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads a release's APK and installs it over this app with Android's package
 * installer: the system asks the user to confirm, nothing opens in a browser.
 * The download address must pass [UpdateDownloadPolicy] (this project's own GitHub
 * repositories, and GitHub's asset hosts on the hops between), and the downloaded
 * APK must pass [trustedUpdate] (this package, the release's version, this app's
 * signing certificate) before the installer is opened.
 */
object AppUpdater {
    sealed interface State {
        data object Idle : State
        /** [progress] 0..1, or -1 while the size is unknown. */
        data class Downloading(val progress: Float) : State
        /** Handed to the system installer, waiting for the user's confirmation. */
        data object Installing : State
        /** Android needs "Install unknown apps" allowed for Myspoti first. */
        data object NeedsPermission : State
        /**
         * Not enough free space for the update, seen before anything was downloaded
         * (or reported by the installer afterwards). [message] is the whole sentence
         * the dialog shows, as-is — not wrapped in the generic failure frame.
         */
        data class NoSpace(val message: String) : State
        /**
         * [onlyByHand]: Android refused the APK over the installed app (signed with another key,
         * or another package): retrying cannot help, the release page is the way.
         */
        data class Failed(val message: String, val onlyByHand: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private const val ACTION = "app.myspoti.UPDATE_STATUS"
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            // Every hop of the download (the first address and each redirect) must be one
            // UpdateDownloadPolicy trusts; a redirect anywhere else fails the download.
            .addNetworkInterceptor(okhttp3.Interceptor { chain ->
                val url = chain.request().url.toString()
                if (!UpdateDownloadPolicy.isDownloadHop(url)) throw java.io.IOException("untrusted download address")
                chain.proceed(chain.request())
            })
            .build()
    }

    /** Android lets this app install packages (the "Install unknown apps" switch). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page with Myspoti's "Install unknown apps" switch. */
    fun openInstallSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Downloads [release]'s APK, then starts the install. Updates [state] as it goes. */
    suspend fun update(context: Context, release: UpdateChecker.Release) {
        val app = context.applicationContext
        if (!canInstall(app)) { _state.value = State.NeedsPermission; return }
        // Fail fast on space, before a byte moves: the staged download and the
        // installer's own staging copy coexist during the swap, so an update needs
        // about twice its size free (Tyson's v16 install died at the installer with
        // INSTALL_FAILED_INSUFFICIENT_STORAGE after a full download). A failed
        // StatFs read must never block an update: the check then simply passes.
        if (release.sizeBytes > 0) {
            val usable = runCatching { android.os.StatFs(app.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val shortfallMb = updateSpaceShortfallMb(usable, release.sizeBytes)
            if (shortfallMb > 0) {
                _state.value = State.NoSpace(app.getString(app.myspoti.engine.R.string.update_no_space, shortfallMb))
                return
            }
        }
        _state.value = State.Downloading(-1f)
        val apk = File(File(app.cacheDir, "updates"), "scadify-${release.version}.apk")
        val result = runCatching {
            download(release, apk)
            // Not this app, not the release's version, or signed with another key: not installed.
            if (!trustedUpdate(app, apk, release.version)) throw java.io.IOException("the downloaded file is not a Scadify update signed like this app")
            install(app, apk)
        }
        // The installer session holds its own copy once committed; a failed download left a partial one.
        runCatching { apk.delete() }
        result.exceptionOrNull()?.let {
            android.util.Log.w("AppUpdater", "update failed", it)
            _state.value = State.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    fun reset() { _state.value = State.Idle }

    private suspend fun download(release: UpdateChecker.Release, file: File): Unit = withContext(Dispatchers.IO) {
        require(UpdateDownloadPolicy.isReleaseAssetUrl(release.apkUrl)) { "untrusted download address" }
        if (release.sizeBytes > UpdateDownloadPolicy.MAX_APK_BYTES) throw java.io.IOException("the download is too big (${release.sizeBytes} bytes)")
        val dir = file.parentFile!!.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val total = r.body.contentLength()
            if (total > UpdateDownloadPolicy.MAX_APK_BYTES) throw java.io.IOException("the download is too big ($total bytes)")
            var done = 0L
            var shown = -1
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > UpdateDownloadPolicy.MAX_APK_BYTES) throw java.io.IOException("the download is too big (over ${UpdateDownloadPolicy.MAX_APK_BYTES} bytes)")
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) { shown = pct; _state.value = State.Downloading(pct / 100f) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw java.io.IOException("download cut at $done / $total bytes")
        }
    }

    /** Streams the APK into an installer session; [StatusReceiver] gets the outcome. */
    private suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        _state.value = State.Installing
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // User action stays required (the default): without the
            // UPDATE_PACKAGES_WITHOUT_USER_ACTION permission a NOT_REQUIRED flag
            // would be ignored anyway; the confirmation is the user's last check.
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("scadify.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context, id,
                    Intent(ACTION).setPackage(context.packageName).setClass(context, StatusReceiver::class.java),
                    // Mutable: the installer adds the status extras.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
                committed = true
            }
        } finally {
            // A session that never reached the commit would stay open, holding a copy of the APK.
            if (!committed) runCatching { installer.abandonSession(id) }
        }
    }

    /** The installer's answer: ask the user to confirm, or report the failure. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                // On success this process is replaced by the new version.
                PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle
                PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = State.Idle
                else -> {
                    // The raw installer text stays in the log; the dialog gets a plain
                    // sentence (a raw "INSTALL_FAILED_..." string is what Tyson saw).
                    val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    android.util.Log.w("AppUpdater", "install failed: status $status: $raw")
                    _state.value = when (updateFailureKind(status, raw)) {
                        UpdateFailureKind.STORAGE ->
                            State.NoSpace(context.getString(app.myspoti.engine.R.string.update_no_space_install))
                        UpdateFailureKind.MISMATCH ->
                            State.Failed(context.getString(app.myspoti.engine.R.string.sys_update_mismatch), onlyByHand = true)
                        UpdateFailureKind.OTHER ->
                            State.Failed(context.getString(app.myspoti.engine.R.string.update_install_failed_plain))
                    }
                }
            }
        }
    }
}

/** Bytes that must be free before an update of [apkBytes] starts: the staged
 *  download and the installer's own staging copy coexist during the swap. */
internal fun updateSpaceRequiredBytes(apkBytes: Long): Long = apkBytes * 2

/**
 * Whole MB the user still has to free before an update of [apkBytes] fits in
 * [usableBytes] — 0 when it fits. Rounded up to a whole 10 MB so the number
 * asked for carries a little slack for the installer's own variance.
 */
internal fun updateSpaceShortfallMb(usableBytes: Long, apkBytes: Long): Long {
    val missing = updateSpaceRequiredBytes(apkBytes) - usableBytes
    if (missing <= 0) return 0
    val mb = (missing + 999_999) / 1_000_000
    return (mb + 9) / 10 * 10
}

/** What an installer failure means, in the dialog's terms. */
internal enum class UpdateFailureKind { STORAGE, MISMATCH, OTHER }

/**
 * Classifies a PackageInstaller failure from its status code and the raw
 * EXTRA_STATUS_MESSAGE (which carries the "INSTALL_FAILED_..." name). STORAGE:
 * the phone ran out of room. MISMATCH: the APK cannot go over the installed
 * app (invalid APK, signature or provider conflicts) — retrying cannot help.
 * Everything else is OTHER.
 */
internal fun updateFailureKind(status: Int, rawMessage: String?): UpdateFailureKind {
    val raw = rawMessage ?: ""
    return when {
        status == PackageInstaller.STATUS_FAILURE_STORAGE ||
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" in raw -> UpdateFailureKind.STORAGE
        status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
            status == PackageInstaller.STATUS_FAILURE_INVALID ||
            "INSTALL_FAILED_INVALID_APK" in raw ||
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in raw ||
            "INSTALL_FAILED_CONFLICTING_PROVIDER" in raw ||
            "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" in raw ||
            "INSTALL_PARSE_FAILED_BAD_MANIFEST" in raw -> UpdateFailureKind.MISMATCH
        else -> UpdateFailureKind.OTHER
    }
}
