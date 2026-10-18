package com.difft.android.call

import com.difft.android.call.session.CallSessionHolder
import com.difft.android.call.session.command.CallSessionCommands
import com.difft.android.call.session.command.HangupReason
import com.difft.android.call.state.OnGoingCallStateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Accepting an incoming call while another is live: hangs the current call up (when it has a room
 * id) and waits until its session has ended, i.e. [CallSessionHolder.current] is cleared, which is
 * also when a new call may start. Returns true at once with no live session, and false when the
 * session does not end within [timeoutMs].
 */
internal suspend fun awaitCurrentCallRelease(
    holder: CallSessionHolder,
    ogsm: OnGoingCallStateManager,
    commands: CallSessionCommands,
    timeoutMs: Long = 15_000,
): Boolean {
    if (holder.current.value == null) return true
    if (!ogsm.getCurrentRoomId().isNullOrEmpty()) {
        commands.hangup(HangupReason.INCOMING_ACCEPT)
    }
    return withTimeoutOrNull(timeoutMs) {
        holder.current.first { it == null }
        true
    } ?: false
}
