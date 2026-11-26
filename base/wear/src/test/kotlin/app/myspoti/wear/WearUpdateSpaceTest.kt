package app.myspoti.wear

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The watch mirror of the phone's v17 space check and installer-failure
 * mapping: an update needs its download plus the installer's staging copy
 * free at once, and a failure must reach the prompt as a plain sentence,
 * never as the raw "INSTALL_FAILED_..." installer string.
 */
class WearUpdateSpaceTest {

    @Test fun requiredSpaceIsTwiceTheApk() {
        assertEquals(188_487_984L, wearUpdateSpaceRequiredBytes(94_243_992L))
        assertEquals(0L, wearUpdateSpaceRequiredBytes(0L))
    }

    @Test fun enoughSpaceAsksForNothing() {
        val apk = 94_243_992L
        assertEquals(0L, wearUpdateSpaceShortfallMb(188_487_984L, apk))
        assertEquals(0L, wearUpdateSpaceShortfallMb(400_000_000L, apk))
    }

    @Test fun shortfallIsTheMissingAmountRoundedUp() {
        val apk = 100_000_000L // needs 200 MB free
        assertEquals(50L, wearUpdateSpaceShortfallMb(150_000_000L, apk))
        assertEquals(200L, wearUpdateSpaceShortfallMb(0L, apk))
        // One byte short still asks for a freeing round (10 MB steps).
        assertEquals(10L, wearUpdateSpaceShortfallMb(199_999_999L, apk))
    }

    @Test fun storageFailuresMapToStorage() {
        assertEquals(WearUpdateFailureKind.STORAGE, wearUpdateFailureKind(PackageInstaller.STATUS_FAILURE_STORAGE, null))
        assertEquals(WearUpdateFailureKind.STORAGE, wearUpdateFailureKind(
            PackageInstaller.STATUS_FAILURE,
            "INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to override installation location"))
    }

    @Test fun mismatchFailuresMapToMismatch() {
        assertEquals(WearUpdateFailureKind.MISMATCH, wearUpdateFailureKind(PackageInstaller.STATUS_FAILURE_CONFLICT, null))
        assertEquals(WearUpdateFailureKind.MISMATCH, wearUpdateFailureKind(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null))
        assertEquals(WearUpdateFailureKind.MISMATCH, wearUpdateFailureKind(PackageInstaller.STATUS_FAILURE_INVALID, null))
        assertEquals(WearUpdateFailureKind.MISMATCH, wearUpdateFailureKind(
            PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_INVALID_APK: Failed to extract native libraries"))
        assertEquals(WearUpdateFailureKind.MISMATCH, wearUpdateFailureKind(
            PackageInstaller.STATUS_FAILURE, "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES"))
    }

    @Test fun anythingElseIsGeneric() {
        assertEquals(WearUpdateFailureKind.OTHER, wearUpdateFailureKind(PackageInstaller.STATUS_FAILURE_BLOCKED, null))
        assertEquals(WearUpdateFailureKind.OTHER, wearUpdateFailureKind(
            PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals(WearUpdateFailureKind.OTHER, wearUpdateFailureKind(PackageInstaller.STATUS_FAILURE, null))
    }
}
