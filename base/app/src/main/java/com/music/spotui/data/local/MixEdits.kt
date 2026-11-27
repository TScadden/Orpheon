/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.local

import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import app.myspoti.automix.MixPlan
import com.music.spotui.di.Graph
import com.music.spotui.extensions.ExtensionAudio
import org.json.JSONObject
import java.io.File

/** One junction's hand-placed transition (the Mix editor): what the listener chose for a pair. */
data class MixEdit(
    val style: MixPlan.Style,
    /** The window's length in bars of the outgoing song (a cut ignores it: its length is two beats). */
    val bars: Int,
    /** The window ends this many bars before the outgoing song's last bar. */
    val endBarOffset: Int,
    /** The DJ EQ and bass swap for this transition, over the global setting. */
    val eq: Boolean,
) {
    /** What identifies this exact edit, so a changed edit replaces a plan made from the old one. */
    val fingerprint: String get() = "$style|$bars|$endBarOffset|$eq"
}

/**
 * The Mix editor's saved plans, in the app's files like the local playlists: keyed by the
 * playlist and the ORDERED PAIR of track identities (the identity each row plays under — see
 * [trackIdentity]), never by row positions, so a plan follows its two songs when the playlist
 * is reordered, and dies with neither. The player prefers a saved plan for the pair it is
 * about to play in that playlist; "Reset to auto" in the editor deletes it.
 */
object MixEdits {
    /** playlistKey + "\n" + identity A + "\n" + identity B → the edit. Compose state: playlist pages redraw their markers from it. */
    val edits: SnapshotStateMap<String, MixEdit> = mutableStateMapOf()
    @Volatile private var loaded = false

    private fun file() = File(Graph.app.filesDir, "mix_edits.json")

    /** The playlist a play context names, or null (the queue, an album, a preview: no saved plans there). */
    fun playlistKeyOf(contextUri: String): String? = when {
        contextUri.startsWith("localplaylist:") -> "local:" + contextUri.removePrefix("localplaylist:")
        contextUri.startsWith("scadify:imported:") -> "imported:" + contextUri.removePrefix("scadify:imported:")
        else -> null
    }

    /** The play context a local playlist hands the player, so its junctions find their edits. */
    fun localContextUri(playlistId: String) = "localplaylist:$playlistId"

    /**
     * The identity a row plays under: an extension song is its source id (the URI's own query
     * changes between asks), anything else is the URI itself — the same rule AutoMix asks by.
     */
    fun trackIdentity(uri: Uri): String =
        if (uri.scheme == ExtensionAudio.SCHEME) "ext:" + uri.lastPathSegment.orEmpty() else uri.toString()

    fun key(playlistKey: String, idA: String, idB: String) = "$playlistKey\n$idA\n$idB"

    fun get(playlistKey: String, idA: String, idB: String): MixEdit? {
        ensureLoaded()
        return edits[key(playlistKey, idA, idB)]
    }

    /** Whether this junction carries a saved plan (the playlist page's marker reads this). */
    fun has(playlistKey: String, idA: String, idB: String): Boolean = get(playlistKey, idA, idB) != null

    fun set(playlistKey: String, idA: String, idB: String, edit: MixEdit) {
        ensureLoaded()
        edits[key(playlistKey, idA, idB)] = edit
        write()
    }

    /** "Reset to auto": the pair's edit goes; the engine's own plan returns at the next play. */
    fun clear(playlistKey: String, idA: String, idB: String) {
        ensureLoaded()
        if (edits.remove(key(playlistKey, idA, idB)) != null) write()
    }

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        runCatching { file().takeIf { it.exists() }?.readText()?.let { read(it) } }
        loaded = true
    }

    @Synchronized private fun write() {
        runCatching {
            val tmp = File(Graph.app.filesDir, "mix_edits.json.tmp")
            tmp.writeText(writeText())
            tmp.renameTo(file())
        }
    }

    /** The store's whole text form, for the round-trip test (the write side of [write]). */
    internal fun writeText(): String {
        val root = JSONObject()
        for ((k, e) in edits) root.put(k, JSONObject()
            .put("style", e.style.name).put("bars", e.bars).put("offset", e.endBarOffset).put("eq", e.eq))
        return root.toString()
    }

    internal fun read(text: String) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val names = root.names() ?: return
        for (i in 0 until names.length()) {
            val k = names.getString(i)
            val o = root.optJSONObject(k) ?: continue
            val style = runCatching { MixPlan.Style.valueOf(o.optString("style")) }.getOrNull() ?: continue
            edits[k] = MixEdit(style, o.optInt("bars", 8).coerceIn(1, 32), o.optInt("offset", 0).coerceAtLeast(0), o.optBoolean("eq", true))
        }
    }
}
