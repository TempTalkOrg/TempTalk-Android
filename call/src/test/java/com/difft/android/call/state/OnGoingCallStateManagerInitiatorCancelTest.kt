package com.difft.android.call.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test










class OnGoingCallStateManagerInitiatorCancelTest {

    @Test
    fun `flag defaults off, mark sets it, and both start-point and end-point clear it`() {
        val manager = OnGoingCallStateManager()
        manager.bindSession(SESSION)

        
        assertFalse(manager.isInitiatorPreConnectCancelled())

        
        manager.publish(SESSION) { markInitiatorPreConnectCancelled() }
        assertTrue(manager.isInitiatorPreConnectCancelled())

        
        
        manager.publish(SESSION) { setClientCallId("client-call-id-1") }
        assertFalse(manager.isInitiatorPreConnectCancelled())

        
        manager.publish(SESSION) { markInitiatorPreConnectCancelled() }
        assertTrue(manager.isInitiatorPreConnectCancelled())
        assertTrue(manager.endPresence(SESSION))
        assertFalse(manager.isInitiatorPreConnectCancelled())
    }

    private companion object {
        const val SESSION = 7L
    }
}
