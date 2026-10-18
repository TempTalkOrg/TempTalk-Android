package com.difft.android.call.session

import androidx.annotation.MainThread
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.handler.CallExitHandler
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.session.command.HangupReason
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns how one call session ends. Remote ends, out-of-page hang-ups and the page's own exits all
 * converge on [finish], which sets the sticky [finishRequest] the call page renders (tip, then its
 * end sequence).
 *
 * Remote ends run in a fixed order: the foreground service is stopped, the caller ringback is stopped
 * when the callee rejected or ended before the call connected, the hang-up tone plays, then the
 * finish request is set. Only remote ends play the tone.
 *
 * Threading: [onRemoteControl], [onTimeout], [hangup], [leaveWithoutPage], [requestExit], [attachUi]
 * and [detachUi] run on main. [markEnding] and [finish] are thread-safe: the exit handler finishes
 * from `appScope` coroutines.
 */
@Suppress("LongParameterList", "TooManyFunctions") // the end path's collaborators, bound once by CallSessionGraph
internal class CallEndController(
    private val params: CallSessionParams,
    private val ogsm: OnGoingCallStateManager,
    private val publisher: OngoingCallPublisher,
    private val ringtone: CallRingtoneManager,
    private val callDurationSeconds: () -> Long,
    private val currentCallType: () -> String,
    private val stopForegroundService: (tag: String) -> Unit,
    private val exitHandlerFactory: (CallExitContext) -> CallExitHandler,
    private val endSessionWithoutUi: () -> Unit,
    private val showToast: (tipRes: Int) -> Unit,
    /** The session's main scope: runs the exit fallback. */
    private val scope: CoroutineScope,
) {
    private val ending = AtomicBoolean(false)
    private val hangupInFlight = AtomicBoolean(false)

    /** How many call pages are attached. Written on main (attach/detach), read in [finish] on any thread. */
    @Volatile var uiAttachCount = 0
        private set

    /** Whether a call page has ever attached to this session; main-confined. */
    var everAttached = false
        private set

    /** Whether the call is ending: the first terminal path has run. */
    val isEnding: Boolean get() = ending.get()

    // Main-confined: armed by every exit, cancelled at session end.
    private var exitFallback: Job? = null

    // Main-confined: the session was ended for a finish no page rendered.
    private var pendingFinishEnded = false

    // Built on first exit use from the call intent's conversation id and call type.
    private val exitHandler: CallExitHandler by lazy {
        exitHandlerFactory(CallExitContext(params.callIntent.conversationId, params.callIntent.callType))
    }

    private val _finishRequest = MutableStateFlow<CallFinishRequest?>(null)
    val finishRequest: StateFlow<CallFinishRequest?> = _finishRequest.asStateFlow()

    /** First call wins and publishes `isInCallEnding`; returns whether this call was the first. */
    fun markEnding(): Boolean = ending.compareAndSet(false, true).also { first ->
        if (first) publisher.publish { setIsInCallEnding(true) }
    }

    /** Remote CALLEND / HANGUP / REJECT. Dropped unless this call is live in [roomId] and not already ending. */
    @MainThread
    fun onRemoteControl(type: CallActionType, roomId: String) {
        if (type !in REMOTE_END_TYPES) return
        if (!shouldProcess(roomId) || !markEnding()) {
            L.i { "[Call][End] remote ${type.type} dropped roomId=$roomId ending=${ending.get()} inCalling=${ogsm.isInCalling()}" }
            return
        }
        L.i { "[Call][End] remote ${type.type} accepted roomId=$roomId" }
        stopForegroundService("remote ${type.type}")
        if (type != CallActionType.HANGUP && shouldStopCallerRingTone()) {
            ringtone.stopRingTone(tag = "reject: callee reject the call")
        }
        ringtone.playHangupRingTone()
        finish(CallFinishRequest.remote(type))
    }

    /**
     * Local hang-up from outside the page. Needs a published room id, and is dropped while the call
     * is already ending or another out-of-page hang-up is in flight, so a repeated tap cannot send
     * the end signaling twice.
     */
    @MainThread
    fun hangup(reason: HangupReason) {
        val roomId = ogsm.getCurrentRoomId()
        if (roomId == null || ending.get() || !hangupInFlight.compareAndSet(false, true)) {
            L.i { "[Call][End] hangup(${reason.logTag}) dropped hasRoomId=${roomId != null} ending=${ending.get()}" }
            return
        }
        L.i { "[Call][End] hangup(${reason.logTag}) roomId=$roomId" }
        leave(roomId, reason.logTag)
    }

    /**
     * Leaves a call no page ever attached to (the session's first-attach watchdog), with or without
     * a room id: the exit decision cancels a call still ringing and hangs up a connected one.
     * Dropped while the call is ending or a hang-up is in flight.
     */
    @MainThread
    fun leaveWithoutPage() {
        if (ending.get() || !hangupInFlight.compareAndSet(false, true)) {
            L.i { "[Call][End] leave without page dropped ending=${ending.get()}" }
            return
        }
        L.i { "[Call][End] leave without page" }
        leave(ogsm.getCurrentRoomId(), "noPage")
    }

    /** Runs the exit decision with LEAVE (see [runExit]). */
    private fun leave(roomId: String?, tag: String) =
        runExit(tag, CallEndType.LEAVE) { exitParams(roomId, exitHandler.context) }

    /**
     * Runs the exit decision, then arms the exit fallback. A handler that throws finishes locally at
     * once and frees the hang-up gate.
     */
    private inline fun runExit(tag: String, endType: CallEndType, exitParams: () -> CallExitParams) {
        try {
            exitHandler.handleExit(exitParams(), endType)
            armExitFallback(tag)
        } catch (e: Exception) {
            L.e(e) { "[Call][End] exit($tag) failed, finishing locally" }
            hangupInFlight.set(false)
            finish(CallFinishRequest.LOCAL)
        }
    }

    /**
     * The end signaling settles only through its callbacks (RTM completion, then HTTP), so a stalled
     * network would keep the call ending forever. After [EXIT_FALLBACK_MS] with no finish, the call
     * ends locally; a late finish from the signaling is then a no-op.
     */
    private fun armExitFallback(tag: String) {
        if (_finishRequest.value != null) return
        exitFallback?.cancel()
        exitFallback = scope.launch {
            delay(EXIT_FALLBACK_MS)
            if (_finishRequest.value != null) return@launch
            L.w { "[Call][End] exit($tag) not settled in ${EXIT_FALLBACK_MS / 1000} s, finishing locally" }
            hangupInFlight.set(false)
            finish(CallFinishRequest.LOCAL)
        }
    }

    /** Session end: the fallback must not fire into a finished call. */
    @MainThread
    fun cancelExitFallback() {
        exitFallback?.cancel()
        exitFallback = null
    }

    /**
     * 1v1 no-answer or remote-left timeout. Dropped unless this call is live in [roomId] and not
     * already ending; plays no tone and sends no signaling.
     */
    @MainThread
    fun onTimeout(roomId: String) {
        if (!shouldProcess(roomId)) {
            L.i { "[Call][End] timeout dropped roomId=$roomId ending=${ending.get()} inCalling=${ogsm.isInCalling()}" }
            return
        }
        L.i { "[Call][End] timeout accepted roomId=$roomId" }
        finish(CallFinishRequest.TIMEOUT)
    }

    /**
     * In-page exit; no guard (END-for-all after LEAVE stays legitimate). Arms the exit fallback like
     * a page-less leave: the page can go away while the end signaling is pending, and the call must
     * still end.
     */
    @MainThread
    fun requestExit(params: CallExitParams, endType: CallEndType) = runExit("pageExit", endType) { params }

    /**
     * The single terminal sink. Sticky and set once; with no page attached the session ends itself.
     * Off main, the last page can detach between the attach-count read and the set, after the
     * session's last-detach check found no request: the re-check on main ends the session then.
     */
    fun finish(request: CallFinishRequest) {
        markEnding()
        val noUi = uiAttachCount == 0
        if (!_finishRequest.compareAndSet(null, if (noUi) request.copy(handledWithoutUi = true) else request)) return
        if (!noUi) {
            // Main.immediate: inline (and a no-op) on main, posted from any other thread.
            scope.launch { if (uiAttachCount == 0) endPendingFinishWithoutPage() }
            return
        }
        L.w { "[Call][End] finish(${request.reason}) with no UI attached, ending session directly" }
        request.tipRes?.let(showToast)
        endSessionWithoutUi()
    }

    @MainThread
    fun attachUi() {
        uiAttachCount++
        everAttached = true
    }

    /** Returns how many pages are still attached. */
    @MainThread
    fun detachUi(): Int {
        uiAttachCount = (uiAttachCount - 1).coerceAtLeast(0)
        return uiAttachCount
    }

    /**
     * The last page went away before rendering the finish request it was handed (its collector runs
     * one main pass after [finish]): show the tip as an app toast and end the session, as [finish]
     * does with no page attached. A no-op without a request, when it was already handled that way,
     * or once it ran.
     */
    @MainThread
    fun endPendingFinishWithoutPage() {
        val request = _finishRequest.value ?: return
        if (request.handledWithoutUi || pendingFinishEnded) return
        pendingFinishEnded = true
        L.w { "[Call][End] last page detached before finishing (${request.reason}), ending session directly" }
        request.tipRes?.let(showToast)
        endSessionWithoutUi()
    }

    private fun shouldProcess(roomId: String) =
        !ending.get() && ogsm.isInCalling() && roomId == ogsm.getCurrentRoomId()

    private fun shouldStopCallerRingTone() = params.callRole == CallRole.CALLER && callDurationSeconds() == 0L

    private fun exitParams(roomId: String?, ctx: CallExitContext) = CallExitParams(
        roomId = roomId,
        callerId = params.callIntent.callerId,
        callRole = params.callRole,
        callType = currentCallType(),
        conversationId = ctx.conversationId,
    )

    private companion object {
        val REMOTE_END_TYPES = setOf(CallActionType.CALLEND, CallActionType.HANGUP, CallActionType.REJECT)

        // Longer than a normal RTM + HTTP round trip: ending earlier would let the session's last
        // cleanup step cancel an RTM send still pending, and the HTTP hang-up queued behind it.
        const val EXIT_FALLBACK_MS = 10_000L
    }
}
