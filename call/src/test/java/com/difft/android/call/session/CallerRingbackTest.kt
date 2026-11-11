package com.difft.android.call.session

import com.difft.android.call.manager.CallRingtoneManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test


class CallerRingbackTest {

    private val ringtoneManager = mockk<CallRingtoneManager>(relaxed = true)
    private val ringback = CallerRingback(ringtoneManager)

    @Test
    fun `a stop that lands before the start request is issued keeps the ringback from ever playing`() {
        var plays = 0

        ringback.stop("callee already in room")
        ringback.start { plays++ }

        assertEquals(0, plays)
        verify(exactly = 1) { ringtoneManager.stopCallerRingback("callee already in room") }
    }

    @Test
    fun `a start before the stop plays and the stop then releases it`() {
        var plays = 0

        ringback.start { plays++ }
        ringback.stop("callee already in room")

        assertEquals(1, plays)
        verify(exactly = 1) { ringtoneManager.stopCallerRingback("callee already in room") }
    }

    @Test
    fun `a new session's ringback is not affected by an earlier session's stop`() {
        var plays = 0
        ringback.stop("earlier call")

        CallerRingback(ringtoneManager).start { plays++ }

        assertEquals(1, plays)
    }
}
