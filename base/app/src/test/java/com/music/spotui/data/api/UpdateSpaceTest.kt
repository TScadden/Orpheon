package com.music.spotui.data.api

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pre-download space check and the installer-failure mapping (v17): an
 * update needs its download plus the installer's staging copy free at once,
 * and a failure must reach the dialog as a plain sentence, never as the raw
 * "INSTALL_FAILED_..." installer string Tyson saw on the v16 prompt.
 */
class UpdateSpaceTest {

    @Test fun requiredSpaceIsTwiceTheApk() {
        assertEquals(272_618_962L, updateSpaceRequiredBytes(136_309_481L))
        assertEquals(0L, updateSpaceRequiredBytes(0L))
    }

    @Test fun enoughSpaceAsksForNothing() {
        val apk = 100_000_000L
        assertEquals(0L, updateSpaceShortfallMb(200_000_000L, apk))
        assertEquals(0L, updateSpaceShortfallMb(500_000_000L, apk))
    }

    @Test fun shortfallIsTheMissingAmountRoundedUp() {
        val apk = 100_000_000L // needs 200 MB free
        assertEquals(50L, updateSpaceShortfallMb(150_000_000L, apk))
        assertEquals(200L, updateSpaceShortfallMb(0L, apk))
        // One byte short still asks for a freeing round (10 MB steps).
        assertEquals(10L, updateSpaceShortfallMb(199_999_999L, apk))
        // Tyson's shape: a 136 MB update with 100 MB free asks for 180 MB.
        assertEquals(180L, updateSpaceShortfallMb(100_000_000L, 136_309_481L))
    }

    @Test fun storageFailuresMapToStorage() {
        assertEquals(UpdateFailureKind.STORAGE, updateFailureKind(PackageInstaller.STATUS_FAILURE_STORAGE, null))
        // The exact raw string behind Tyson's v16 failure.
        assertEquals(UpdateFailureKind.STORAGE, updateFailureKind(
            PackageInstaller.STATUS_FAILURE,
            "INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to override installation location"))
        // Storage wins even when the status code alone would read as a conflict.
        assertEquals(UpdateFailureKind.STORAGE, updateFailureKind(
            PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_INSUFFICIENT_STORAGE"))
    }

    @Test fun mismatchFailuresMapToMismatch() {
        assertEquals(UpdateFailureKind.MISMATCH, updateFailureKind(PackageInstaller.STATUS_FAILURE_CONFLICT, null))
        assertEquals(UpdateFailureKind.MISMATCH, updateFailureKind(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null))
        assertEquals(UpdateFailureKind.MISMATCH, updateFailureKind(PackageInstaller.STATUS_FAILURE_INVALID, null))
        assertEquals(UpdateFailureKind.MISMATCH, updateFailureKind(
            PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_INVALID_APK: Failed to extract native libraries"))
        assertEquals(UpdateFailureKind.MISMATCH, updateFailureKind(
            PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertEquals(UpdateFailureKind.MISMATCH, updateFailureKind(
            PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_CONFLICTING_PROVIDER"))
        assertEquals(UpdateFailureKind.MISMATCH, updateFailureKind(
            PackageInstaller.STATUS_FAILURE, "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES"))
    }

    @Test fun anythingElseIsGeneric() {
        assertEquals(UpdateFailureKind.OTHER, updateFailureKind(PackageInstaller.STATUS_FAILURE_BLOCKED, null))
        assertEquals(UpdateFailureKind.OTHER, updateFailureKind(
            PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals(UpdateFailureKind.OTHER, updateFailureKind(PackageInstaller.STATUS_FAILURE, null))
    }
}
