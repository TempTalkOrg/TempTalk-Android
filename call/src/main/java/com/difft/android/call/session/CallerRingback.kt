package com.difft.android.call.session

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.manager.CallRingtoneManager








internal class CallerRingback(private val ringtoneManager: CallRingtoneManager) {
    private var stopped = false

    
    @Synchronized
    fun start(play: () -> Unit) {
        if (stopped) {
            L.i { "[Call] CallerRingback start skipped: already stopped" }
            return
        }
        play()
    }

    @Synchronized
    fun stop(tag: String) {
        stopped = true
        ringtoneManager.stopCallerRingback(tag)
    }
}
