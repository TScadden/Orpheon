package app.myspoti.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time default-on migration of the watch's automatic update check (Scadify v16). */
class WearUpdatePrefTest {
    @Test fun beforeTheMigrationTheCheckIsOnWhateverWasStored() {
        // An install that saved the switch off under the old setup is turned on once.
        assertTrue(wearAutoUpdateAfterMigration(stored = false, migrated = false))
        assertTrue(wearAutoUpdateAfterMigration(stored = true, migrated = false))
        assertTrue(wearAutoUpdateAfterMigration(stored = null, migrated = false))
    }

    @Test fun afterTheMigrationADeliberateOffSticks() {
        assertFalse(wearAutoUpdateAfterMigration(stored = false, migrated = true))
        assertTrue(wearAutoUpdateAfterMigration(stored = true, migrated = true))
        // Nothing stored (the migration ran, the switch was never touched): still on.
        assertTrue(wearAutoUpdateAfterMigration(stored = null, migrated = true))
    }
}
