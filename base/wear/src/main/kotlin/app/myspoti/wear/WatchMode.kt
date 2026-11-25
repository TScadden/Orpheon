package app.myspoti.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the music plays, as the watch's mode switch puts it: on the phone (the watch mirrors
 * and drives it) or on the watch itself (its own player and extensions, no phone needed).
 */
enum class WatchMode { PHONE, WATCH }

/** The chosen [WatchMode]: Phone until told otherwise, kept on the watch between runs. */
object WatchModes {
    private const val KEY = "mode"

    private val _mode = MutableStateFlow(WatchMode.PHONE)
    val mode: StateFlow<WatchMode> = _mode

    @Volatile private var store: WatchStore? = null

    fun init(context: Context) {
        if (store != null) return
        val s = WatchStore(context.applicationContext)
        store = s
        _mode.value = parse(s.get(KEY))
    }

    fun set(mode: WatchMode) {
        _mode.value = mode
        store?.put(KEY, mode.name.lowercase())
    }

    /** What a kept value means; anything unreadable is the default, Phone mode. Pure. */
    fun parse(text: String?): WatchMode =
        if (text?.trim().equals("watch", ignoreCase = true)) WatchMode.WATCH else WatchMode.PHONE
}
