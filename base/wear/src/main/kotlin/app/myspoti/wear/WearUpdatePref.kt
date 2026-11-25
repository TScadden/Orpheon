package app.myspoti.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The pure half of [WearUpdatePref]'s one-time migration (mirrors the phone's
 * autoUpdateAfterMigration): before the migration, on (whatever was stored — the check
 * now reads the watch's own releases repo and Tyson wants it on); after it, the stored
 * value, so a deliberate off on the watch sticks.
 */
internal fun wearAutoUpdateAfterMigration(stored: Boolean?, migrated: Boolean): Boolean =
    if (migrated) stored ?: true else true

/**
 * The watch's automatic update check switch (on by default), kept on the watch between
 * runs — the wear port of the phone's AutoUpdateCheckPref (Scadify v16), following
 * [WatchModes]' shape. Off means no automatic check; a check asked for on the watch
 * still works, it reads the same release feed.
 *
 * A one-time migration turns it on for installs that carry the switch saved from
 * before, and records that it ran ([wearAutoUpdateAfterMigration]); a later deliberate
 * off sticks.
 */
object WearUpdatePref {
    private const val KEY = "auto_update_check"
    private const val MIGRATED_KEY = "auto_update_check_default_migrated"

    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on

    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        val p = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val migrated = p.getBoolean(MIGRATED_KEY, false)
        val stored = if (p.contains(KEY)) p.getBoolean(KEY, true) else null
        val value = wearAutoUpdateAfterMigration(stored, migrated)
        if (!migrated) p.edit().putBoolean(KEY, value).putBoolean(MIGRATED_KEY, true).apply()
        _on.value = value
        ready = true
    }

    fun set(context: Context, value: Boolean) {
        _on.value = value
        context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
    }
}
