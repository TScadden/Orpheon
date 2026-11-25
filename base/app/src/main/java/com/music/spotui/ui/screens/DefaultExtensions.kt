package com.music.spotui.ui.screens

import app.myspoti.extensions.Catalog

/**
 * The extensions Scadify offers to install during setup (the "Your extensions" step in
 * SetupScreen), matched by their manifest name against whatever catalogues the store can
 * see on this phone (the official index, GitHub discovery, the repositories the user
 * added) — never by a hardcoded address, so a default a phone cannot see is said to be
 * unavailable instead of fetched from a guessed place. Resolution happens at install time
 * and takes the newest listing, so the normal update path keeps them current afterwards.
 */
object DefaultExtensions {
    val NAMES = listOf("Fresh Home", "Melzo", "Solid Source")

    sealed interface State {
        /** The catalogues have not answered yet. */
        object Looking : State
        /** On the phone already (by name in the registry). */
        object Installed : State
        /** Offered by a catalogue this phone can see: (index address, newest entry). */
        data class Ready(val repo: String, val entry: Catalog.Entry) : State
        /** No catalogue this phone can see carries it. */
        object Unavailable : State
    }

    /** [name]'s state: installed first, then the newest listing across [catalogs]. */
    fun state(name: String, catalogs: List<Pair<String, Catalog>>, installedNames: Set<String>, stillLooking: Boolean): State {
        if (installedNames.any { it.equals(name, ignoreCase = true) }) return State.Installed
        val matches = catalogs.flatMap { (repo, catalog) -> catalog.extensions.filter { it.name.equals(name, ignoreCase = true) }.map { repo to it } }
        val best = matches.maxWithOrNull(compareBy { it.second.versionCode })
        return when {
            best != null -> State.Ready(best.first, best.second)
            stillLooking -> State.Looking
            else -> State.Unavailable
        }
    }
}
