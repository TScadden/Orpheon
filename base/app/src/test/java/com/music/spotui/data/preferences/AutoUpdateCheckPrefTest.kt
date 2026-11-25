package com.music.spotui.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time default-on migration of the automatic update check (Scadify v14). */
class AutoUpdateCheckPrefTest {
    @Test fun beforeTheMigrationTheCheckIsOnWhateverWasStored() {
        // An install that saved the switch off under the old setup is turned on once.
        assertTrue(autoUpdateAfterMigration(stored = false, migrated = false))
        assertTrue(autoUpdateAfterMigration(stored = true, migrated = false))
        assertTrue(autoUpdateAfterMigration(stored = null, migrated = false))
    }

    @Test fun afterTheMigrationADeliberateOffSticks() {
        assertFalse(autoUpdateAfterMigration(stored = false, migrated = true))
        assertTrue(autoUpdateAfterMigration(stored = true, migrated = true))
        // Nothing stored (the migration ran, the switch was never touched): still on.
        assertTrue(autoUpdateAfterMigration(stored = null, migrated = true))
    }
}
