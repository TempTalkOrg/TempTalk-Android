package com.difft.android.chat.ui

import android.os.Bundle
import android.os.Process

/**
 * The conversation viewport saved across a fold/unfold recreation: either the message at its top
 * with its pixel offset from the list's top padding, or [BOTTOM] when the newest message was fully
 * on screen. The retained ViewModel replays its last scroll action (a jump, the first unread, a page
 * load) into the new view, so neither the list's own saved position nor that action says where the
 * user was; this anchor is applied on the first non-empty list instead.
 */
internal data class MessageAnchor(val timestamp: Long?, val offsetPx: Int) {

    /** How a restored anchor is applied to the list the new view received. */
    sealed interface Restore {
        /** The newest message was on screen: show the bottom of the list. */
        data object Bottom : Restore

        /** The message is loaded: scroll to [position] keeping [offsetPx]. */
        data class InList(val position: Int, val offsetPx: Int) : Restore

        /** Not in the loaded window: load around it with the existing jump. */
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

        /**
         * [BOTTOM] only holds within the process that saved it: after process death the new
         * ViewModel opens at the first unread message, which a bottom restore would skip and mark read.
         */
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
