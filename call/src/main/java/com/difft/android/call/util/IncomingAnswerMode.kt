package com.difft.android.call.util

import com.difft.android.call.util.ScreenDeviceUtil.ScreenState


enum class IncomingAnswerMode {
    
    TAP,

    
    SLIDE,
}

object IncomingAnswerModeResolver {

    






    fun atCreate(fullScreenLaunch: Boolean, state: ScreenState): IncomingAnswerMode =
        if (fullScreenLaunch || state.wokenByCall) IncomingAnswerMode.SLIDE else IncomingAnswerMode.TAP

    





    fun onResume(current: IncomingAnswerMode, atCreate: ScreenState, now: ScreenState): IncomingAnswerMode {
        val unlockedSinceCreate = atCreate.keyguardLocked && !now.keyguardLocked && now.interactive
        return if (current == IncomingAnswerMode.SLIDE && unlockedSinceCreate) IncomingAnswerMode.TAP else current
    }
}
