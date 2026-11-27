/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui

import com.music.spotui.automix.AutoMix
import com.music.spotui.ui.screens.MixFailureCopy
import com.music.spotui.ui.screens.mixFailureCopy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Mix editor's failure screen: the engine's recorded reason decides the line, in plain
 * words — a song that stalled is not "couldn't be analyzed", and mobile data keeps its own
 * wording whatever detail rides along.
 */
class MixFailureCopyTest {
    @Test fun mobileDataKeepsItsOwnLine() {
        assertEquals(MixFailureCopy.MobileData, mixFailureCopy(AutoMix.Reason.MobileData, null))
        assertEquals(MixFailureCopy.MobileData, mixFailureCopy(AutoMix.Reason.MobileData, AutoMix.RefusalDetail.Stalled))
    }

    @Test fun eachRefusalDetailHasItsOwnLine() {
        assertEquals(MixFailureCopy.Stalled, mixFailureCopy(AutoMix.Reason.NotAnalyzed, AutoMix.RefusalDetail.Stalled))
        assertEquals(MixFailureCopy.TooLong, mixFailureCopy(AutoMix.Reason.NotAnalyzed, AutoMix.RefusalDetail.TooLong))
        assertEquals(MixFailureCopy.NoStream, mixFailureCopy(AutoMix.Reason.NotAnalyzed, AutoMix.RefusalDetail.NoStream))
        assertEquals(MixFailureCopy.SourceError, mixFailureCopy(AutoMix.Reason.NotAnalyzed, AutoMix.RefusalDetail.SourceError))
    }

    @Test fun anythingElseFallsBackToTheGenericLine() {
        assertEquals(MixFailureCopy.Generic, mixFailureCopy(AutoMix.Reason.NotAnalyzed, null))
        assertEquals(MixFailureCopy.Generic, mixFailureCopy(null, null))
        assertEquals(MixFailureCopy.Generic, mixFailureCopy(AutoMix.Reason.Frugal, null))
    }
}
