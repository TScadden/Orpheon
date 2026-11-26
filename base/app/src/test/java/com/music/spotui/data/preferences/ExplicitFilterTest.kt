package com.music.spotui.data.preferences

import app.myspoti.api.Explicitness
import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The explicit filter (v23): only a KNOWN-explicit song is ever hidden or
 * skipped, and only while "Allow explicit music" is off. A song no source has
 * judged — UNKNOWN, which is most phone files and every unreleased file — is
 * never touched, and neither is a song a source judged CLEAN.
 */
class ExplicitFilterTest {

    private fun song(title: String, explicit: Boolean = false, status: Explicitness = if (explicit) Explicitness.EXPLICIT else Explicitness.UNKNOWN) =
        SongsModel(1, title, "Album", "Singer", "", "spotify:track:x", explicit = explicit, explicitStatus = status)

    private val loud = song("Loud", status = Explicitness.EXPLICIT)
    private val loudByFlag = song("Loud By Flag", explicit = true)
    private val quiet = song("Quiet", status = Explicitness.CLEAN)
    private val unjudged = song("Unjudged")
    private val phoneFile = SongsModel(2, "Unreleased Demo", "Demos", "Me", "", "local:content://media/9")

    @Test fun onlyKnownExplicitSongsAreHiddenAndOnlyWhileOff() {
        assertTrue(ExplicitFilter.hides(loud, allowed = false))
        assertTrue(ExplicitFilter.hides(loudByFlag, allowed = false))
        assertFalse(ExplicitFilter.hides(quiet, allowed = false))
        assertFalse(ExplicitFilter.hides(unjudged, allowed = false))
        // The hard rule: a phone file with no explicit data is never hidden.
        assertFalse(ExplicitFilter.hides(phoneFile, allowed = false))
        // Allowed: nothing is hidden, whatever its mark.
        assertFalse(ExplicitFilter.hides(loud, allowed = true))
        assertFalse(ExplicitFilter.hides(loudByFlag, allowed = true))
    }

    @Test fun visibleListsDropOnlyTheKnownExplicitOnes() {
        val all = listOf(loud, quiet, unjudged, loudByFlag, phoneFile)
        assertEquals(listOf(quiet, unjudged, phoneFile), ExplicitFilter.visible(all, allowed = false))
        assertEquals(all, ExplicitFilter.visible(all, allowed = true))
        assertEquals(2, ExplicitFilter.hiddenCount(all, allowed = false))
        assertEquals(0, ExplicitFilter.hiddenCount(all, allowed = true))
    }

    @Test fun aQueueSkipsAKnownExplicitSongOnlyWhileOff() {
        assertTrue(ExplicitFilter.blocks(explicit = true, allowed = false))
        assertFalse(ExplicitFilter.blocks(explicit = true, allowed = true))
        assertFalse(ExplicitFilter.blocks(explicit = false, allowed = false))
    }

    @Test fun theBadgeShowsOnlyForKnownExplicit() {
        // The badge everywhere judges by knownExplicit: a stated EXPLICIT, by the
        // tri-state or by the older flag alone. CLEAN and UNKNOWN never badge.
        assertTrue(loud.knownExplicit)
        assertTrue(loudByFlag.knownExplicit)
        assertFalse(quiet.knownExplicit)
        assertFalse(unjudged.knownExplicit)
        assertFalse(phoneFile.knownExplicit)
    }
}
