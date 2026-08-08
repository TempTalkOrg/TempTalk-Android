package com.difft.android.call.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle coverage for the initiator pre-connect cancel flag added for PR #1111.
 *
 * The flag lives in the [OnGoingCallStateManager] `@Singleton`, so its clearing is the load-bearing
 * safety property: if a stale `true` survived into the next outgoing call, every future initiator
 * would be misread as "already cancelled" and its callee would never ring. This pins the two-sided
 * clearing contract — start-point ([OnGoingCallStateManager.setClientCallId], the once-per-start
 * identity anchor) and end-point ([OnGoingCallStateManager.reset]) — so neither can silently regress.
 */
class OnGoingCallStateManagerInitiatorCancelTest {

    @Test
    fun `flag defaults off, mark sets it, and both start-point and end-point clear it`() {
        val manager = OnGoingCallStateManager()

        // Default: not cancelled.
        assertFalse(manager.isInitiatorPreConnectCancelled())

        // Exit during window W sets the intent.
        manager.markInitiatorPreConnectCancelled()
        assertTrue(manager.isInitiatorPreConnectCancelled())

        // Start-point clear: a new outgoing call establishes its identity via setClientCallId,
        // which must wipe any stale intent so this call is never misread as pre-cancelled.
        manager.setClientCallId("client-call-id-1")
        assertFalse(manager.isInitiatorPreConnectCancelled())

        // End-point clear: reset() (call teardown) also clears it.
        manager.markInitiatorPreConnectCancelled()
        assertTrue(manager.isInitiatorPreConnectCancelled())
        manager.reset()
        assertFalse(manager.isInitiatorPreConnectCancelled())
    }
}
