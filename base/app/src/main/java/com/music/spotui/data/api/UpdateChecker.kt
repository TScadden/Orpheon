package com.music.spotui.data.api

import android.content.Context
import app.myspoti.engine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * New version check against the GitHub releases of [BuildConfig.UPDATE_REPO]
 * (set by the CI build; empty in local builds, which never check).
 *
 * Scadify v14: the repo is the releases-only companion TScadden/scadify-releases —
 * every release there is a Scadify build, numbered like the app itself ("14", "15", …:
 * the tag is "v14" and the APK's versionName is "14", see app/build.gradle.kts), so the
 * checker reads GitHub's "latest" directly, the way Tyson's Island's updater does.
 * Automatic checks run every 3 hours (Tyson's cadence, on by default — see
 * [com.music.spotui.data.preferences.AutoUpdateCheckPref]). The release's APK is
 * downloaded and installed in the app ([AppUpdater]), after [UpdateDownloadPolicy]
 * and [trustedUpdate] have had their say.
 */
object UpdateChecker {
    /** [apkUrl]: the release's signed APK; blank when the release has none (then its page opens). */
    data class Release(val version: String, val pageUrl: String, val notes: String = "", val apkUrl: String = "", val sizeBytes: Long = 0)

    /** Launch / resume check: not more often than this (the 3-hour cadence). */
    const val INTERVAL_MS = 3 * 3_600_000L
    /** While the app stays open, the check runs again this often. */
    const val PERIODIC_MS = 3 * 3_600_000L
    /** After a failed automatic check (offline, rate limit), wait this long before trying again. */
    private const val RETRY_MS = 10 * 60_000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).addInterceptor(com.music.spotui.data.DataSaver.interceptor).build() }

    /** Answer of [refresh]: the check always asks GitHub, whatever was cached. */
    sealed interface Outcome {
        data class Found(val release: Release) : Outcome
        data object UpToDate : Outcome
        /** [rateLimited]: GitHub refused for too many requests (about an hour on a shared network). */
        data class Failed(val rateLimited: Boolean) : Outcome
    }

    private sealed interface Fetch {
        data class Ok(val release: Release?) : Fetch
        data class Err(val rateLimited: Boolean) : Fetch
    }

    /** When the last successful check ran (epoch ms), 0 if never. */
    fun lastChecked(context: Context): Long =
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("checked_at", 0L)

    /**
     * Automatic check (launch, resume, periodic). [manual]: a release dismissed with "Later" is
     * offered again (the automatic check stays quiet about it). Skipped when the last
     * successful check is less than [INTERVAL_MS] old; a failed one is retried after [RETRY_MS]
     * and never erases the release already known.
     */
    suspend fun check(context: Context, manual: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return@withContext null
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked_at", 0L) < INTERVAL_MS || now - prefs.getLong("tried_at", 0L) < RETRY_MS) return@withContext pending(prefs, manual)
        store(prefs, fetch(repo, force = false), now)
        pending(prefs, manual)
    }

    /**
     * "Check now" from Settings: bypasses the throttle, the stored answer and every HTTP/CDN
     * cache, and says what happened.
     */
    suspend fun refresh(context: Context): Outcome = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return@withContext Outcome.UpToDate
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val result = fetch(repo, force = true)
        store(prefs, result, System.currentTimeMillis())
        when (result) {
            is Fetch.Err -> Outcome.Failed(result.rateLimited)
            is Fetch.Ok -> pending(prefs, manual = true)?.let { Outcome.Found(it) } ?: Outcome.UpToDate
        }
    }

    private fun store(prefs: android.content.SharedPreferences, result: Fetch, now: Long) {
        val e = prefs.edit()
        when (result) {
            // A failure keeps the last answer: "up to date" must not come from a failed request.
            is Fetch.Err -> e.putLong("tried_at", now)
            is Fetch.Ok -> e.putLong("checked_at", now).putLong("tried_at", 0L)
                .putString("version", result.release?.version).putString("url", result.release?.pageUrl)
                .putString("notes", result.release?.notes).putString("apk", result.release?.apkUrl)
                .putLong("size", result.release?.sizeBytes ?: 0L)
        }
        e.apply()
    }

    private fun fetch(repo: String, force: Boolean): Fetch = try {
        // "latest" is the newest full release; the releases-only repo carries nothing
        // else, so no list has to be picked through. (GitHub never serves a pre-release
        // here, so a beta build finds nothing — Scadify's shipped builds are all on the
        // stable channel.)
        val bust = if (force) "?_=${System.currentTimeMillis()}" else ""
        val url = "https://api.github.com/repos/$repo/releases/latest$bust"
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .apply { if (force) { header("Cache-Control", "no-cache, no-store, max-age=0"); header("Pragma", "no-cache") } }.build()
        http.newCall(request).execute().use { r ->
            // No release published yet.
            if (r.code == 404) return@use Fetch.Ok(null)
            if (!r.isSuccessful) return@use Fetch.Err(r.code == 429 || (r.code == 403 && r.header("x-ratelimit-remaining") == "0"))
            Fetch.Ok(releaseFrom(JSONObject(r.body.string())))
        }
    } catch (e: Exception) { Fetch.Err(false) }

    /**
     * A GitHub release as this app's update offer, or null when it is not one: a draft,
     * the other channel's (a pre-release for a stable build, a full release for a beta),
     * a tag that is not a version of the app, or a release carrying no APK this app may
     * take ([apkAsset]).
     */
    internal fun releaseFrom(release: JSONObject, beta: Boolean = isBeta): Release? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease") != beta) return null
        val tag = release.optString("tag_name")
        if (!isVersionTag(tag)) return null
        val asset = apkAsset(release) ?: return null
        return Release(tag.removePrefix("v"), release.optString("html_url"), cleanNotes(release.optString("body")), asset.url, asset.sizeBytes)
    }

    /** The newer release last seen, dismissed or not (no request): what Settings > About shows. */
    fun known(context: Context): Release? =
        pending(context.getSharedPreferences("updates", Context.MODE_PRIVATE), true)

    /** The latest release known, if newer than this build and (unless [manual]) not dismissed. */
    private fun pending(prefs: android.content.SharedPreferences, manual: Boolean = false): Release? {
        val version = prefs.getString("version", null) ?: return null
        val url = prefs.getString("url", null) ?: return null
        if (!manual && prefs.getString("dismissed", null) == version) return null
        return Release(version, url, prefs.getString("notes", null).orEmpty(), prefs.getString("apk", null).orEmpty(),
            prefs.getLong("size", 0L)).takeIf { isNewer(version, BuildConfig.VERSION_NAME) }
    }

    /** An APK of a release: where to download it and how big GitHub says it is. */
    internal data class ApkAsset(val url: String, val sizeBytes: Long)

    /** "v14", "v2.4.0", "2.5.0-beta.1": a version of the app, not some other tag ("automix2-build205"). */
    internal fun isVersionTag(tag: String): Boolean = Regex("v?\\d+(\\.\\d+)*(-[0-9A-Za-z.]+)?").matches(tag)

    /**
     * The release's APK download link, Island-style: the first asset whose name ends in
     * ".apk" and does not contain "debug", whose address [UpdateDownloadPolicy] trusts
     * (this project's own repositories only) and whose size is within the cap. The feed
     * is Scadify's own releases repo, so no name scheme has to be matched — the policy
     * check on the address and [trustedUpdate] after the download are the guards. No
     * such asset: no link, and the release page opens instead.
     */
    internal fun apkAsset(release: JSONObject): ApkAsset? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name").lowercase()
            val url = asset.optString("browser_download_url")
            val size = asset.optLong("size", 0L)
            if (name.endsWith(".apk") && "debug" !in name && size in 0..UpdateDownloadPolicy.MAX_APK_BYTES &&
                UpdateDownloadPolicy.isReleaseAssetUrl(url)) return ApkAsset(url, size)
        }
        return null
    }

    fun dismiss(context: Context, release: Release) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putString("dismissed", release.version).apply()
    }

    /**
     * The release description as plain lines for the update window: the CHANGELOG section,
     * without GitHub's generated part, Markdown emphasis or link syntax.
     */
    fun cleanNotes(body: String): String = body
        .substringBefore("**Full Changelog**").substringBefore("## What's Changed")
        .lines().map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .joinToString("\n") { line ->
            line.replace("**", "").replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("^(\\s*)[-*] "), "$1• ")
        }
        .trim()

    /** This build is a beta ("2.0.0-beta.1"). */
    val isBeta get() = BuildConfig.VERSION_NAME.contains("-beta")

    /**
     * "14" > "13", "1.10.0" > "1.9.2" (numbers, not text; non-numeric parts count as 0).
     * A release is newer than its betas ("2.0.0" > "2.0.0-beta.3"), and
     * betas compare by number ("2.0.0-beta.2" > "2.0.0-beta.1").
     */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // Same version number: release > beta, then the higher beta.
        fun beta(v: String) = if (!v.contains('-')) Int.MAX_VALUE else Regex("(\\d+)$").find(v)?.value?.toIntOrNull() ?: 0
        return beta(candidate) > beta(current)
    }
}
