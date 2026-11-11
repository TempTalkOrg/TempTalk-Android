package com.difft.android.call.session

import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.CallIntent
import com.difft.android.call.data.VoicePreset





data class CallSessionParams(
    val e2eeEnable: Boolean,
    val callIntent: CallIntent,
    val callConfig: CallConfig,
    val callRole: CallRole,
    




    val initialVoicePreset: VoicePreset,
    
    val launchKey: String = "",
    
    val needAppLock: Boolean = false,
    
    val entryWaitToken: Int = ComposeDialogManager.NO_CLAIM,
)
