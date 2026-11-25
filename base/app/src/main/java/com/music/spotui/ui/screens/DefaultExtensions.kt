package com.music.spotui.ui.screens

import app.myspoti.extensions.Catalog

/**
 * The extensions Scadify offers to install during setup (the "Your extensions" step in
 * SetupScreen), matched against the phone's installed registry first and then whatever
 * catalogues the store can see on this phone (the official index, GitHub discovery, the
 * repositories the user added) — never by a hardcoded address, so a default a phone cannot
 * see is said to be unavailable instead of fetched from a guessed place. Resolution happens
 * at install time and takes the newest listing, so the normal update path keeps them
 * current afterwards.
 */
object DefaultExtensions {
    val NAMES = listOf("Fresh Home", "Melzo", "Solid Source")

    /** One extension on the phone, as the classification needs it: its manifest identity. */
    data class Installed(val id: String, val name: String, val version: String, val versionCode: Int)

    sealed interface State {
        /** The catalogues have not answered yet. */
        object Looking : State
        /** On the phone already, and no catalogue this phone can see has a newer one. */
        object Installed : State
        /** On the phone already; a catalogue has a newer version: installing runs the update prompt. */
        data class Update(val repo: String, val entry: Catalog.Entry, val installedVersion: String) : State
        /** Offered by a catalogue this phone can see: (index address, newest entry). */
        data class Ready(val repo: String, val entry: Catalog.Entry) : State
        /** Not installed, and no catalogue this phone can see carries it. */
        object Unavailable : State
    }

    /**
     * [name]'s state. The installed registry is matched FIRST, by identity: a catalogue
     * entry's id is its manifest id, the same id the registry keeps, so an installed
     * extension is recognised whatever its package calls itself; the name is the fallback
     * for a default no catalogue carries (it can then only be recognised by the name its
     * package installed under). An installed match with a newer catalogue listing is an
     * [State.Update], never a fresh install; a namesake listing under a different id is
     * not an update of what is on the phone.
     */
    fun state(name: String, catalogs: List<Pair<String, Catalog>>, installed: List<Installed>, stillLooking: Boolean): State {
        val matches = catalogs.flatMap { (repo, catalog) -> catalog.extensions.filter { it.name.equals(name, ignoreCase = true) }.map { repo to it } }
        val best = matches.maxWithOrNull(compareBy { it.second.versionCode })
        val byId = best?.let { b -> installed.firstOrNull { it.id == b.second.id } }
        val mine = byId ?: installed.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
        if (mine != null) {
            return if (byId != null && best != null && best.second.versionCode > mine.versionCode) {
                State.Update(best.first, best.second, mine.version)
            } else {
                State.Installed
            }
        }
        return when {
            best != null -> State.Ready(best.first, best.second)
            stillLooking -> State.Looking
            else -> State.Unavailable
        }
    }
}
