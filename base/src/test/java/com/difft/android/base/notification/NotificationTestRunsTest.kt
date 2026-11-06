package com.difft.android.base.notification

import org.junit.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationTestRunsTest {
    private fun owner(host: NotificationTestOwner.Host) = NotificationTestOwner(UUID.randomUUID().toString(), host)

    @Test
    fun `hosted and shell owners compete for one exact lease`() {
        val leases = NotificationTestRuns()
        val hosted = owner(NotificationTestOwner.Host.HOSTED)
        val shell = owner(NotificationTestOwner.Host.SHELL)
        val first = assertNotNull(leases.claim(hosted, NotificationTestKind.BANNER))
        assertNull(leases.claim(hosted, NotificationTestKind.CALL))
        assertNull(leases.claim(shell, NotificationTestKind.CALL))
        leases.release(first.copy(runId = UUID.randomUUID().toString()))
        assertTrue(leases.owns(first))
        leases.release(first)
        val next = assertNotNull(leases.claim(shell, NotificationTestKind.CALL))
        leases.release(first)
        assertTrue(leases.owns(next))
        assertFalse(leases.matches(first.owner.ownerId, first.runId, first.kind))
    }

    @Test
    fun `ticket codec rejects unbounded corrupt and unknown schemas`() {
        assertNull(NotificationTestCallTicket.decode("x".repeat(2_049)))
        assertNull(NotificationTestCallTicket.decode("2|${UUID.randomUUID()}|1000|fp|61000|PENDING"))
        assertNull(NotificationTestCallTicket.decode("1|${UUID.randomUUID()}|1000|fp|61001|PENDING"))
        assertNull(NotificationTestCallTicket.decode("1|not-a-uuid|1000|fp|61000|PENDING"))
    }
}
