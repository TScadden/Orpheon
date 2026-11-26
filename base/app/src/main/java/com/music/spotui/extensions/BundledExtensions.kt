package com.music.spotui.extensions

import android.app.Application
import android.content.Context
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.Origin
import app.myspoti.host.android.Logbook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Extensions Scadify itself ships, installed by the app on its own: for now the SoundCloud
 * source (Tyson's, from his own repository), bundled as the release package of
 * `TScadden/scadify-soundcloud` v1.0.1 in the assets. Tyson's call (Nov 26, 2026): it is
 * on by default, so nobody has to go and install it by hand.
 *
 * The seed runs once, off the startup path: the first launch that finds the extension
 * missing installs it through [Extensions.install] — the same call the store makes, with
 * the repository as its [Origin], so the extension's own update checks keep it current
 * from there and [Extensions.firstOnInstall] puts it first wherever it acts, enabled.
 * A flag in the preferences remembers the seed has been dealt with; from then on the
 * extension is the user's — uninstalling or turning it off is never undone by the seed,
 * and a copy installed by hand (or a newer one the updater brought) is left alone.
 */
object BundledExtensions {

    const val SOUNDCLOUD_ID = "io.github.tscadden.scadify.soundcloud"

    /** The bundled package, in the app's assets: the v1.0.1 release's own extension.zip. */
    const val SOUNDCLOUD_ASSET = "soundcloud-extension.zip"
    private const val SOUNDCLOUD_REPO = "TScadden/scadify-soundcloud"
    private const val SOUNDCLOUD_TAG = "v1.0.1"

    private const val PREFS = "bundled_extensions"
    private const val KEY_SEEDED = "seeded_soundcloud"
    private const val KEY_ATTEMPTS = "seed_attempts_soundcloud"

    /** A failing seed is retried on this many launches at most, then given up on. */
    private const val MAX_ATTEMPTS = 3

    /** The seed's whole decision: only a phone missing the extension, whose seed has not been settled, is seeded. */
    fun shouldSeed(installed: Boolean, seeded: Boolean): Boolean = !installed && !seeded

    /** A bundled package that does not parse, or parses as another extension: the same on every launch of this build. */
    class BadBundledPackage(message: String) : Exception(message)

    /** Whether [t] ends the seed for good (see [seed]): a bad bundled package, yes; anything else gets its launches. */
    fun isFinalFailure(t: Throwable): Boolean = t is BadBundledPackage

    /** After the extension system is up (Graph.init): the seed, off the startup path, never blocking it. */
    fun start(application: Application) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { seed(application) }
                .onFailure { Logbook.log("ext $SOUNDCLOUD_ID: the bundled install did not run: ${it.message}") }
        }
    }

    private suspend fun seed(application: Application) {
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installed = Extensions.registry.value.find(SOUNDCLOUD_ID) != null
        if (!shouldSeed(installed, prefs.getBoolean(KEY_SEEDED, false))) {
            // Already there (installed by hand, or by an earlier seed): the seed's work is
            // done, and the flag says so — a later uninstall is then never undone.
            if (installed) prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            return
        }
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0)
        try {
            val zip = application.assets.open(SOUNDCLOUD_ASSET).use { it.readBytes() }
            val pkg = runCatching { ExtensionPackage.read(zip) }
                .getOrElse { throw BadBundledPackage("the bundled SoundCloud package does not parse: ${it.message}") }
            if (pkg.manifest.id != SOUNDCLOUD_ID)
                throw BadBundledPackage("the bundled package is ${pkg.manifest.id}, not $SOUNDCLOUD_ID")
            Extensions.install(
                zip, pkg, Grants.keys(pkg.manifest), source = "bundled",
                clashes = Extensions.clashes(pkg.manifest),
                noticeAccepted = true,
                origin = Origin(SOUNDCLOUD_REPO, SOUNDCLOUD_TAG, tag = true, asset = true),
                accepted = true,
            )
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            Logbook.log("ext $SOUNDCLOUD_ID: installed from the app bundle (v${pkg.manifest.version})")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logbook.log("ext $SOUNDCLOUD_ID: the bundled install failed: ${t.message}")
            // A failure must never crash or block a launch, and never repeat forever: a bad
            // bundled package is final at once; anything else (the store's kill switch, a
            // half-written registry) is retried on the next launches, up to MAX_ATTEMPTS.
            if (isFinalFailure(t) || attempts + 1 >= MAX_ATTEMPTS) prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            else prefs.edit().putInt(KEY_ATTEMPTS, attempts + 1).apply()
        }
    }
}
