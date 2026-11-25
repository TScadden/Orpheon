package com.music.spotui.ui.components

/**
 * The selection of a multi-select list (Local Files, a local playlist): which rows are
 * chosen, by their own key. Pure set moves, kept apart from the screens so the rules are
 * testable: a tap toggles one row, Select all adds every shown row, leaving the mode is
 * the empty set.
 */
object MultiSelect {
    fun <K> toggle(selected: Set<K>, key: K): Set<K> =
        if (key in selected) selected - key else selected + key

    fun <K> selectAll(selected: Set<K>, keys: Collection<K>): Set<K> = selected + keys
}
