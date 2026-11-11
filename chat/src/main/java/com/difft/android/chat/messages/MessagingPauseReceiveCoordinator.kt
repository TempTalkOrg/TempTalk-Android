package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.chat.PendingMessageHelper
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.chat.websocket.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton










@Singleton
class MessagingPauseReceiveCoordinator @Inject constructor(
    private val messagingGate: MessagingGate,
    @param:AppCoroutineScope private val scope: CoroutineScope,
    
    private val webSocketManager: Provider<WebSocketManager>,
    private val pendingMessageHelper: Provider<PendingMessageHelper>,
    private val pendingMessageProcessor: Provider<PendingMessageProcessor>,
    private val failedMessageProcessor: Provider<FailedMessageProcessor>,
) {
    private val started = AtomicBoolean(false)

    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            var open = false
            var openedBefore = false
            messagingGate.onChanged.collect { state ->
                val nowOpen = state == MessagingPauseState.Open
                if (nowOpen == open) return@collect
                open = nowOpen
                if (nowOpen) {
                    onOpened(afterPause = openedBefore)
                    openedBefore = true
                } else {
                    onClosed()
                }
            }
        }
    }

    private fun onOpened(afterPause: Boolean) {
        safely("resume_socket") { webSocketManager.get().resume() }
        safely("deferred_pull") { pendingMessageHelper.get().runDeferredPull() }
        if (afterPause) {
            safely("drain_trigger") {
                pendingMessageProcessor.get().triggerProcess()
                failedMessageProcessor.get().triggerProcess()
            }
        }
    }

    private fun onClosed() {
        safely("pause_socket") { webSocketManager.get().pause() }
    }

    
    private inline fun safely(step: String, block: () -> Unit) {
        try {
            block()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            L.e { "[MigrationPause] event=receive_coordinator_failed step=$step ${e.stackTraceToString()}" }
        }
    }
}
