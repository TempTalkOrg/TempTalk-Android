package com.difft.android.call.util

import com.difft.android.call.util.ScreenDeviceUtil.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingAnswerModeTest {

    private val awake = ScreenState(interactive = true, keyguardLocked = false)
    private val awakeLocked = ScreenState(interactive = true, keyguardLocked = true)
    private val asleep = ScreenState(interactive = false, keyguardLocked = false)

    @Test
    fun `full-screen launch slides even when the display already reads interactive`() {
        assertEquals(IncomingAnswerMode.SLIDE, IncomingAnswerModeResolver.atCreate(fullScreenLaunch = true, state = awake))
    }

    @Test
    fun `display off or keyguard up slides without the flag`() {
        assertEquals(IncomingAnswerMode.SLIDE, IncomingAnswerModeResolver.atCreate(fullScreenLaunch = false, state = asleep))
        assertEquals(IncomingAnswerMode.SLIDE, IncomingAnswerModeResolver.atCreate(fullScreenLaunch = false, state = awakeLocked))
    }

    @Test
    fun `unlocked phone in use taps`() {
        assertEquals(IncomingAnswerMode.TAP, IncomingAnswerModeResolver.atCreate(fullScreenLaunch = false, state = awake))
    }

    @Test
    fun `slide falls back to tap once the keyguard has been dismissed`() {
        assertEquals(
            IncomingAnswerMode.TAP,
            IncomingAnswerModeResolver.onResume(IncomingAnswerMode.SLIDE, atCreate = awakeLocked, now = awake),
        )
    }

    @Test
    fun `slide stays while the keyguard is still up or was never up`() {
        assertEquals(
            IncomingAnswerMode.SLIDE,
            IncomingAnswerModeResolver.onResume(IncomingAnswerMode.SLIDE, atCreate = awakeLocked, now = awakeLocked),
        )
        assertEquals(
            IncomingAnswerMode.SLIDE,
            IncomingAnswerModeResolver.onResume(IncomingAnswerMode.SLIDE, atCreate = asleep, now = awake),
        )
    }

    @Test
    fun `tap never switches to slide on resume`() {
        assertEquals(
            IncomingAnswerMode.TAP,
            IncomingAnswerModeResolver.onResume(IncomingAnswerMode.TAP, atCreate = awake, now = awakeLocked),
        )
    }
}
