package com.difft.android.chat.websocket.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [keepAliveUnansweredForCadence]: only an unanswered send judged on the timer tick disconnects. */
class KeepAliveUnansweredRuleTest {

    @Test
    fun `timer tick with the previous keep-alive unanswered disconnects`() {
        assertTrue(keepAliveUnansweredForCadence(wakeReason = null, previousSendElapsed = 1_000L, lastResponseElapsed = 900L))
    }

    @Test
    fun `answered keep-alive keeps the socket`() {
        assertFalse(keepAliveUnansweredForCadence(wakeReason = null, previousSendElapsed = 1_000L, lastResponseElapsed = 1_200L))
    }

    @Test
    fun `no keep-alive sent on this socket yet keeps it`() {
        assertFalse(keepAliveUnansweredForCadence(wakeReason = null, previousSendElapsed = 0L, lastResponseElapsed = 0L))
    }

    @Test
    fun `wake events never judge the previous send`() {
        WakeReason.entries.forEach { reason ->
            assertFalse(reason.name, keepAliveUnansweredForCadence(reason, previousSendElapsed = 1_000L, lastResponseElapsed = 900L))
        }
    }
}
