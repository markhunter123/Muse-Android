package io.github.markhunter123.muse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #migration: the settings-page one-time hint to uninstall the official Shizuku app. */
class OfficialShizukuHintTest {
    @Test fun showsOnlyWhenOfficialInstalledAndReadyAndNeverShownBefore() {
        assertTrue(OfficialShizukuHint.needed(officialInstalled = true, ready = true, shown = false))
        assertFalse("the official app is gone: nothing to migrate", OfficialShizukuHint.needed(false, true, false))
        assertFalse("shell not ready: the hint would not be true yet", OfficialShizukuHint.needed(true, false, false))
        assertFalse("one-time: never shown twice", OfficialShizukuHint.needed(true, true, true))
    }

    @Test fun officialPackageIsTheShizukuManager() {
        assertEquals("moe.shizuku.privileged.api", OfficialShizukuHint.OFFICIAL_PACKAGE)
    }
}
