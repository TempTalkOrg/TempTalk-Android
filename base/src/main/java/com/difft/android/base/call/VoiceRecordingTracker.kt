package com.difft.android.base.call

import com.difft.android.base.log.lumberjack.L

/**
 * Process-level flag tracking whether the chat module's VoiceRecorderView is
 * currently capturing audio.
 *
 * Read by notification / incoming-call paths (MessageNotificationUtil.showCallNotificationNew,
 * MessageNotificationUtil.showCriticalAlert, IncomingCallServiceManager.showIncomingCallUI)
 * to suppress full-screen Activities while the user is recording a voice message — full-screen
 * Activities would cover the chat screen, detach the recorder View, and ship a half-finished voice note.
 *
 * Lives in :base so both :chat (the writer) and :call (a reader) can access it without
 * creating a :call -> :chat dependency.
 *
 * Writer contract: exactly one writer (VoiceRecorderView).
 * All [isRecording] transitions must call [setRecording] so logs stay aligned with the bug timeline.
 *
 * Reader contract: bare point-in-time read; do not cache the value — recording can stop between
 * read and notification post. Re-read at each gate.
 *
 * Thread safety: [isRecording] is @Volatile. Writers may be on Main, IO, or audio-focus listener
 * threads; readers are on whichever thread builds the notification (typically appScope IO).
 */
object VoiceRecordingTracker {

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Set the recording flag. Logged for cross-correlation with notification logs.
     *
     * @param recording new state
     * @param reason caller-supplied short tag for log ("start" / "stop" / "cancel" /
     *               "detach" / "abort"); helps diagnose recording-state
     *               leaks (flag stuck true after the View is gone).
     */
    fun setRecording(recording: Boolean, reason: String) {
        val previous = isRecording
        isRecording = recording
        if (previous != recording) {
            L.i { "[VoiceRecordingTracker] state=$recording reason=$reason" }
        }
    }
}
