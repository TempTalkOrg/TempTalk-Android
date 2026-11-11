package com.difft.android.security

import android.os.Debug
import com.difft.android.base.log.lumberjack.L
import java.io.File

internal object DebuggerDetector {

    
    fun isTracerPidDetected(): Boolean {
        val statusFile = File("/proc/self/status")
        if (!statusFile.exists()) {
            return false
        }
        val tracerLine = runCatching {
            statusFile.useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
            }
        }.getOrNull() ?: return false

        val tracerPid = tracerLine.substringAfter("TracerPid:", "")
            .trim()
            .toIntOrNull()
            ?: return false

        val detected = tracerPid != 0
        if (detected) {
            L.e { "[security] DebuggerDetector tracer detected, tracerPid=$tracerPid" }
        }
        return detected
    }

    
    fun isDebuggerConnected(): Boolean {
        val isDebuggerConnected = Debug.isDebuggerConnected()
        L.i { "[security] DebuggerDetector isDebuggerConnected=$isDebuggerConnected" }
        return isDebuggerConnected
    }
}
