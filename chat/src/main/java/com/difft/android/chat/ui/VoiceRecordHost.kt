package com.difft.android.chat.ui

import com.difft.android.chat.widget.VoiceRecorderView





interface VoiceRecordHost {
    val voiceRecorderView: VoiceRecorderView?

    fun setVoiceRecordOverlayVisible(visible: Boolean)
}
