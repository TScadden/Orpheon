package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The multi-select set moves the Local Files and local playlist screens share. */
class MultiSelectTest {

    @Test fun aTapTogglesOneRow() {
        var selected = MultiSelect.toggle(emptySet<String>(), "a")
        assertEquals(setOf("a"), selected)
        selected = MultiSelect.toggle(selected, "b")
        assertEquals(setOf("a", "b"), selected)
        selected = MultiSelect.toggle(selected, "a")
        assertEquals(setOf("b"), selected)
        // Toggling the last one off is the empty set: the selection mode is over.
        selected = MultiSelect.toggle(selected, "b")
        assertEquals(emptySet<String>(), selected)
    }

    @Test fun selectAllAddsEveryShownRowAndKeepsWhatWasChosen() {
        val selected = MultiSelect.selectAll(setOf("a"), listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), selected)
        // Selecting all again changes nothing.
        assertEquals(selected, MultiSelect.selectAll(selected, listOf("a", "b", "c")))
    }
}
