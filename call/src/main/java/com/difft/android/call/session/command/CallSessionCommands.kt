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











@Singleton
class CallSessionCommands @Inject constructor(
    private val holder: CallSessionHolder,
    dispatchers: CallSessionDispatchers,
) {
    
    private val mainScope = CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineName("CallSessionCommands"))

    
    fun hangup(reason: HangupReason) = onLiveSession("hangup(${reason.logTag})") { it.hangup(reason) }

    
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


enum class HangupReason(val logTag: String) {
    NOTIFICATION("notification"),
    INCOMING_ACCEPT("incomingAccept"),
}
