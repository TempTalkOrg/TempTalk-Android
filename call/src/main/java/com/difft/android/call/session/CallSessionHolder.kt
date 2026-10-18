package com.difft.android.call.session

import androidx.annotation.MainThread
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level owner of the live [CallSession]. A session is started by the call's entry point
 * ([CallSessionLauncher]) before its page exists; pages attach to [current]. One call at a time:
 * a start while a session is live is refused.
 */
@Singleton
class CallSessionHolder @Inject constructor(
    private val sessionFactory: CallSession.Factory,
) {
    private val _current = MutableStateFlow<CallSession?>(null)

    /** The live (not yet ended) session, or null. Cleared on the session's FIRST `end()` call. */
    val current: StateFlow<CallSession?> = _current.asStateFlow()

    /**
     * Creates the session, publishes it as [current] and starts it. Returns null when a session is
     * already live (nothing is created) or when the start failed; a failed start has already ended
     * the session and cleared [current].
     */
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

    /** Ends whatever is current; no-op when none. */
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
        // Identity CAS: only the current session's end clears it.
        if (_current.compareAndSet(session, null)) L.i { "[Call][Session] cleared id=${session.id}" }
    }
}

/**
 * Why a [CallSession] ends. [logTag] is the cleanup-executor reason string (kept stable so log
 * greps keep working). Every reason ends the session the same way; the session `Job` is cancelled
 * by the last cleanup step.
 */
enum class CallSessionEndReason(val logTag: String) {
    /** The call page's finish-effect end path (`doExitClear`). */
    EXIT_CLEAR("doExitClear"),

    /** A finish with no call page attached, or one the last page went away without rendering. */
    NO_UI("noUi"),

    /** [CallSession.start] threw; the partly started session is unwound. */
    START_FAILED("startFailed"),

    /** The room setup (Phase B) failed or was aborted; there is no room to show. */
    ROOM_FAILED("roomFailed"),
}
