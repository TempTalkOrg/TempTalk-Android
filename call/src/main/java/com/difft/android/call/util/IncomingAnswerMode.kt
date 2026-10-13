package com.difft.android.call.util

import com.difft.android.call.util.ScreenDeviceUtil.ScreenState

/** Which control answers an incoming call on the ring screen. */
enum class IncomingAnswerMode {
    /** Two round buttons; the phone is unlocked and in the person's hand. */
    TAP,

    /** Accept is a slide across a track; the call itself lit the screen, so a touch may not be a person. */
    SLIDE,
}

object IncomingAnswerModeResolver {

    /**
     * Mode when the ring screen is created. [fullScreenLaunch] is the primary signal: the system
     * fires a notification's full-screen intent only when the display is off or the keyguard is up,
     * and it is recorded in the intent before the wake lock and `turnScreenOn` light the display —
     * by onCreate `isInteractive` is usually already true, so the live [state] alone under-reports.
     * The live keyguard/display read still covers launches that carry no flag.
     */
    fun atCreate(fullScreenLaunch: Boolean, state: ScreenState): IncomingAnswerMode =
        if (fullScreenLaunch || state.wokenByCall) IncomingAnswerMode.SLIDE else IncomingAnswerMode.TAP

    /**
     * Mode on a later resume. A slide screen falls back to taps once the person has unlocked the
     * phone since creation (keyguard was up, is now down, display on): the phone is in hand, and
     * that is the path the foreground-restore takes when it reorders this screen to the front.
     * Devices with no lock screen never report a keyguard, so they keep the slider.
     */
    fun onResume(current: IncomingAnswerMode, atCreate: ScreenState, now: ScreenState): IncomingAnswerMode {
        val unlockedSinceCreate = atCreate.keyguardLocked && !now.keyguardLocked && now.interactive
        return if (current == IncomingAnswerMode.SLIDE && unlockedSinceCreate) IncomingAnswerMode.TAP else current
    }
}
