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














@Suppress("LongParameterList", "TooManyFunctions") 
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
    
    private val scope: CoroutineScope,
) {
    private val ending = AtomicBoolean(false)
    private val hangupInFlight = AtomicBoolean(false)

    
    @Volatile var uiAttachCount = 0
        private set

    
    var everAttached = false
        private set

    
    val isEnding: Boolean get() = ending.get()

    
    private var exitFallback: Job? = null

    
    private var pendingFinishEnded = false

    
    private val exitHandler: CallExitHandler by lazy {
        exitHandlerFactory(CallExitContext(params.callIntent.conversationId, params.callIntent.callType))
    }

    private val _finishRequest = MutableStateFlow<CallFinishRequest?>(null)
    val finishRequest: StateFlow<CallFinishRequest?> = _finishRequest.asStateFlow()

    
    fun markEnding(): Boolean = ending.compareAndSet(false, true).also { first ->
        if (first) publisher.publish { setIsInCallEnding(true) }
    }

    
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

    




    @MainThread
    fun leaveWithoutPage() {
        if (ending.get() || !hangupInFlight.compareAndSet(false, true)) {
            L.i { "[Call][End] leave without page dropped ending=${ending.get()}" }
            return
        }
        L.i { "[Call][End] leave without page" }
        leave(ogsm.getCurrentRoomId(), "noPage")
    }

    
    private fun leave(roomId: String?, tag: String) =
        runExit(tag, CallEndType.LEAVE) { exitParams(roomId, exitHandler.context) }

    



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

    
    @MainThread
    fun cancelExitFallback() {
        exitFallback?.cancel()
        exitFallback = null
    }

    



    @MainThread
    fun onTimeout(roomId: String) {
        if (!shouldProcess(roomId)) {
            L.i { "[Call][End] timeout dropped roomId=$roomId ending=${ending.get()} inCalling=${ogsm.isInCalling()}" }
            return
        }
        L.i { "[Call][End] timeout accepted roomId=$roomId" }
        finish(CallFinishRequest.TIMEOUT)
    }

    




    @MainThread
    fun requestExit(params: CallExitParams, endType: CallEndType) = runExit("pageExit", endType) { params }

    




    fun finish(request: CallFinishRequest) {
        markEnding()
        val noUi = uiAttachCount == 0
        if (!_finishRequest.compareAndSet(null, if (noUi) request.copy(handledWithoutUi = true) else request)) return
        if (!noUi) {
            
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

    
    @MainThread
    fun detachUi(): Int {
        uiAttachCount = (uiAttachCount - 1).coerceAtLeast(0)
        return uiAttachCount
    }

    





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

        
        
        const val EXIT_FALLBACK_MS = 10_000L
    }
}
