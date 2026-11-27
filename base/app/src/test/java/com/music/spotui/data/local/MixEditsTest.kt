/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.local

import app.myspoti.automix.MixPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mix editor's saved plans: the store round-trips, and the keying is the playlist plus the
 * ordered pair of track identities — a plan follows its two songs across a reorder (it is not
 * keyed on row positions), and the reversed pair is a different junction.
 */
class MixEditsTest {
    private val edit = MixEdit(MixPlan.Style.BLEND, bars = 12, endBarOffset = 2, eq = false)

    @Test fun roundTripKeepsEveryField() {
        MixEdits.edits.clear()
        MixEdits.set("local:p1", "song-a", "song-b", edit)
        val text = MixEdits.writeText()
        MixEdits.edits.clear()
        assertNull(MixEdits.get("local:p1", "song-a", "song-b"))
        MixEdits.read(text)
        assertEquals(edit, MixEdits.get("local:p1", "song-a", "song-b"))
        MixEdits.edits.clear()
    }

    @Test fun theKeyIsThePairNotThePositions() {
        // The same two songs, wherever they sit in the playlist, share one key; the pair
        // reversed (B into A) is another junction with no edit.
        val key = MixEdits.key("local:p1", "song-a", "song-b")
        assertEquals("local:p1\nsong-a\nsong-b", key)
        assertTrue(key != MixEdits.key("local:p1", "song-b", "song-a"))
        assertTrue(key != MixEdits.key("local:p2", "song-a", "song-b"))
        MixEdits.edits.clear()
        MixEdits.set("local:p1", "song-a", "song-b", edit)
        assertTrue(MixEdits.has("local:p1", "song-a", "song-b"))
        assertFalse(MixEdits.has("local:p1", "song-b", "song-a"))
        // Reset to auto deletes exactly that pair's edit.
        MixEdits.clear("local:p1", "song-a", "song-b")
        assertFalse(MixEdits.has("local:p1", "song-a", "song-b"))
        MixEdits.edits.clear()
    }

    @Test fun playlistKeysComeFromPlayContexts() {
        assertEquals("local:abc", MixEdits.playlistKeyOf("localplaylist:abc"))
        assertEquals("imported:xyz", MixEdits.playlistKeyOf("scadify:imported:xyz"))
        // The queue, an album, a preview: no saved plans there.
        assertNull(MixEdits.playlistKeyOf(""))
        assertNull(MixEdits.playlistKeyOf("spotify:album:1"))
        assertNull(MixEdits.playlistKeyOf("mixpreview:"))
        assertEquals("localplaylist:abc", MixEdits.localContextUri("abc"))
    }

    @Test fun aChangedEditHasAnotherFingerprint() {
        // The player compares fingerprints: an edit saved mid-playback replaces the old plan.
        assertTrue(edit.fingerprint != edit.copy(bars = 8).fingerprint)
        assertTrue(edit.fingerprint != edit.copy(eq = true).fingerprint)
        assertTrue(edit.fingerprint != edit.copy(style = MixPlan.Style.FADE).fingerprint)
        assertEquals(edit.fingerprint, edit.copy().fingerprint)
    }
}
