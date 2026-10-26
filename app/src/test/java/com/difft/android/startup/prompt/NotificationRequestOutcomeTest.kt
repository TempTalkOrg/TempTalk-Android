package com.difft.android.startup.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rationale before/after mapping of the system POST_NOTIFICATIONS result (#1355 §4.2). */
class NotificationRequestOutcomeTest {

    private fun map(granted: Boolean = false, rb: Boolean, ra: Boolean) = NotificationRequestOutcome.map(granted, rb, ra)

    @Test
    fun `granted wins whatever the flags`() {
        assertEquals(NotificationRequestOutcome.Granted, map(granted = true, rb = true, ra = true))
    }

    @Test
    fun `first visible denial counts`() {
        assertEquals(NotificationRequestOutcome.Declined(countsAsSystemDenial = true), map(rb = false, ra = true))
    }

    @Test
    fun `second visible denial counts`() {
        assertEquals(NotificationRequestOutcome.Declined(countsAsSystemDenial = true), map(rb = true, ra = false))
    }

    @Test
    fun `dismissed without answering counts`() {
        assertEquals(NotificationRequestOutcome.Declined(countsAsSystemDenial = true), map(rb = true, ra = true))
    }

    @Test
    fun `silent result means the system route is exhausted and the app dialog takes the slot`() {
        // Includes a permanent denial from before the upgrade, which no counter has seen.
        assertEquals(NotificationRequestOutcome.ShowAppDialog, map(rb = false, ra = false))
    }

    @Test
    fun `the system is asked while it can still show its dialog`() {
        assertTrue(NotificationRequestOutcome.useSystemRequest(permissionRequestable = true, rationaleNow = false, systemDenials = 1))
        assertTrue(NotificationRequestOutcome.useSystemRequest(permissionRequestable = true, rationaleNow = true, systemDenials = 2))
        assertFalse(NotificationRequestOutcome.useSystemRequest(permissionRequestable = true, rationaleNow = false, systemDenials = 2))
        assertFalse(NotificationRequestOutcome.useSystemRequest(permissionRequestable = false, rationaleNow = true, systemDenials = 0))
    }
}
