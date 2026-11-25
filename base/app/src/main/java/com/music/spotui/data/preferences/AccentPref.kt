package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The accent colour: one palette per look, picked in Settings by anyone. The choice used to
 * live in the support store (it began as a supporter thank-you); [init] carries the saved
 * colours over from that file, so nobody's pick is lost with the move.
 */
object AccentPref {
    private const val PREFS = "accent_pref"
    /** Where the colours were kept before this store existed. */
    private const val LEGACY_PREFS = "support_reminder"
    /** The single accent of earlier versions, copied to both looks once ([init]). */
    private const val KEY_ACCENT = "accent"
    private const val KEY_ACCENT_CLASSIC = "accent_classic"
    private const val KEY_ACCENT_IMMERSIVE = "accent_immersive"

    /** A palette colour: the app's accent for a look. */
    enum class Accent(val key: String, val color: Color) {
        GREEN("green", Color(0xFF1ED760)),
        BLUE("blue", Color(0xFF509BF5)),
        PURPLE("purple", Color(0xFFAF7AF6)),
        PINK("pink", Color(0xFFF573A0)),
        ORANGE("orange", Color(0xFFFF9E3D)),
    }

    /** The accent chosen for the Classic look. */
    var classicAccent by mutableStateOf(Accent.GREEN)
        private set
    /** The accent chosen for the Immersive look. */
    var immersiveAccent by mutableStateOf(Accent.GREEN)
        private set
    /** A colour was picked for Immersive (green is also the default): the look has its own default accent until then. */
    var immersiveAccentChosen by mutableStateOf(false)
        private set

    /** The accent chosen for a look. */
    fun accentOf(look: Look): Accent = if (look == Look.IMMERSIVE) immersiveAccent else classicAccent

    /** The colour the active look uses for its accent (Spotify green unless another was chosen in Settings). */
    val accentColor: Color get() = accentOf(LookPref.look).color

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_ACCENT_CLASSIC) && !p.contains(KEY_ACCENT_IMMERSIVE)) {
            // First run of this store: carry over what the support store held, if anything.
            val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val edit = p.edit()
            // One accent used to serve both looks: whoever picked one keeps it for each look.
            legacy.getString(KEY_ACCENT, null)?.let {
                edit.putString(KEY_ACCENT_CLASSIC, it).putString(KEY_ACCENT_IMMERSIVE, it)
            }
            legacy.getString(KEY_ACCENT_CLASSIC, null)?.let { edit.putString(KEY_ACCENT_CLASSIC, it) }
            legacy.getString(KEY_ACCENT_IMMERSIVE, null)?.let { edit.putString(KEY_ACCENT_IMMERSIVE, it) }
            edit.apply()
        }
        classicAccent = Accent.entries.firstOrNull { it.key == p.getString(KEY_ACCENT_CLASSIC, "green") } ?: Accent.GREEN
        immersiveAccent = Accent.entries.firstOrNull { it.key == p.getString(KEY_ACCENT_IMMERSIVE, "green") } ?: Accent.GREEN
        immersiveAccentChosen = p.contains(KEY_ACCENT_IMMERSIVE)
    }

    fun setAccent(context: Context, look: Look, value: Accent) {
        if (look == Look.IMMERSIVE) {
            immersiveAccent = value
            immersiveAccentChosen = true
            prefs(context).edit().putString(KEY_ACCENT_IMMERSIVE, value.key).apply()
        } else {
            classicAccent = value
            prefs(context).edit().putString(KEY_ACCENT_CLASSIC, value.key).apply()
        }
    }
}
