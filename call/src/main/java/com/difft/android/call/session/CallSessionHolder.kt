package com.difft.android.call.session

import androidx.annotation.MainThread
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton






@Singleton
class CallSessionHolder @Inject constructor(
    private val sessionFactory: CallSession.Factory,
) {
    private val _current = MutableStateFlow<CallSession?>(null)

    
    val current: StateFlow<CallSession?> = _current.asStateFlow()

    




    @MainThread
    fun start(params: CallSessionParams): CallSession? {
        _current.value?.let {
            L.w { "[Call][Session] start refused: id=${it.id} live" }
            return null
        }
        val session = sessionFactory.create(params, ::onSessionEnded)
        _current.value = session
        if (!session.start()) return null
        L.i {
            "[Call][Session] started id=${session.id} action=${params.callIntent.action} " +
                "type=${params.callIntent.callType} role=${params.callRole}"
        }
        return session
    }

    
    @MainThread
    fun end(reason: CallSessionEndReason) {
        val session = _current.value
        if (session == null) {
            L.i { "[Call][Session] end(${reason.logTag}) with no current session" }
            return
        }
        session.end(reason)
    }

    private fun onSessionEnded(session: CallSession) {
        
        if (_current.compareAndSet(session, null)) L.i { "[Call][Session] cleared id=${session.id}" }
    }
}






enum class CallSessionEndReason(val logTag: String) {
    
    EXIT_CLEAR("doExitClear"),

    
    NO_UI("noUi"),

    
    START_FAILED("startFailed"),

    
    ROOM_FAILED("roomFailed"),
}
