package com.difft.android.base.call

import com.difft.android.base.log.lumberjack.L






















object VoiceRecordingTracker {

    @Volatile
    var isRecording: Boolean = false
        private set

    







    fun setRecording(recording: Boolean, reason: String) {
        val previous = isRecording
        isRecording = recording
        if (previous != recording) {
            L.i { "[VoiceRecordingTracker] state=$recording reason=$reason" }
        }
    }
}
