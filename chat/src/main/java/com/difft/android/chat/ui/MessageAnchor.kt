package com.difft.android.chat.ui

import android.os.Bundle
import android.os.Process








internal data class MessageAnchor(val timestamp: Long?, val offsetPx: Int) {

    
    sealed interface Restore {
        
        data object Bottom : Restore

        
        data class InList(val position: Int, val offsetPx: Int) : Restore

        
        data class Jump(val timestamp: Long) : Restore
    }

    fun resolve(timestamps: List<Long>): Restore {
        val target = timestamp ?: return Restore.Bottom
        val position = timestamps.indexOf(target)
        return if (position >= 0) Restore.InList(position, offsetPx) else Restore.Jump(target)
    }

    fun saveTo(outState: Bundle) {
        if (timestamp == null) {
            outState.putInt(KEY_BOTTOM_PID, Process.myPid())
        } else {
            outState.putLong(KEY_TIMESTAMP, timestamp)
            outState.putInt(KEY_OFFSET, offsetPx)
        }
    }

    companion object {
        private const val KEY_BOTTOM_PID = "message_anchor_bottom_pid"
        private const val KEY_TIMESTAMP = "message_anchor_timestamp"
        private const val KEY_OFFSET = "message_anchor_offset"

        val BOTTOM = MessageAnchor(timestamp = null, offsetPx = 0)

        



        fun from(savedInstanceState: Bundle?, pid: Int = Process.myPid()): MessageAnchor? = when {
            savedInstanceState == null -> null
            savedInstanceState.containsKey(KEY_BOTTOM_PID) ->
                if (savedInstanceState.getInt(KEY_BOTTOM_PID) == pid) BOTTOM else null
            savedInstanceState.containsKey(KEY_TIMESTAMP) ->
                MessageAnchor(savedInstanceState.getLong(KEY_TIMESTAMP), savedInstanceState.getInt(KEY_OFFSET))
            else -> null
        }
    }
}
