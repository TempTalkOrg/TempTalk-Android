package com.difft.android.chat.ui

import com.difft.android.chat.widget.VoiceRecorderView

/**
 * Implemented by the screen that hosts the voice-recording overlay (`cl_voice_record`) above
 * [ChatMessageInputFragment]; the input drives a hold-to-record session through it.
 */
interface VoiceRecordHost {
    val voiceRecorderView: VoiceRecorderView?

    fun setVoiceRecordOverlayVisible(visible: Boolean)
}
