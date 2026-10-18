package com.difft.android.call.session.command

import com.difft.android.base.call.CallActionType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionDispatchers
import com.difft.android.call.session.CallSessionHolder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for call commands that originate outside the call page: the notification hang-up,
 * an incoming-call accept, and remote end signaling. Each command is a direct call on the live
 * [CallSession].
 *
 * Resolves the live session on the main thread ([CallSessionHolder] is main-confined). The scope
 * runs on `Main.immediate`, so a caller already on main (receiver, service, incoming page) runs
 * inline, and any other thread is posted. With no live session there is no call to act on, so the
 * command is dropped with a log.
 */
@Singleton
class CallSessionCommands @Inject constructor(
    private val holder: CallSessionHolder,
    dispatchers: CallSessionDispatchers,
) {
    // No exception handler on purpose: a failing command crashes instead of vanishing.
    private val mainScope = CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineName("CallSessionCommands"))

    /** Local hang-up requested from outside the call page. */
    fun hangup(reason: HangupReason) = onLiveSession("hangup(${reason.logTag})") { it.hangup(reason) }

    /** Remote CALLEND / HANGUP / REJECT for [roomId]; other types are ignored by the session. */
    fun onRemoteControl(type: CallActionType, roomId: String) =
        onLiveSession("remote(${type.type})") { it.onRemoteControl(type, roomId) }

    private inline fun onLiveSession(tag: String, crossinline block: (CallSession) -> Unit) {
        mainScope.launch {
            val session = holder.current.value
            if (session == null) {
                L.i { "[Call][Cmd] $tag dropped: no live session" }
                return@launch
            }
            block(session)
        }
    }
}

/** Where an out-of-page hang-up came from (log tag only; every reason takes the same path). */
enum class HangupReason(val logTag: String) {
    NOTIFICATION("notification"),
    INCOMING_ACCEPT("incomingAccept"),
}
