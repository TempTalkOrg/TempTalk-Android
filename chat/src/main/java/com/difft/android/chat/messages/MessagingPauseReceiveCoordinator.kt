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

/**
 * Applies the migration message pause (#1382) to the receive side, so later work only changes
 * the [MessagingGate] state: closing messaging pauses the socket; opening it resumes the socket,
 * runs a pull that was deferred while closed and, after a pause, re-triggers the local drains
 * whose ticks were skipped.
 *
 * Started once at process start. The gate starts closed (loading) and the socket starts paused,
 * so the first transition acted on is the first opening.
 */
@Singleton
class MessagingPauseReceiveCoordinator @Inject constructor(
    private val messagingGate: MessagingGate,
    @param:AppCoroutineScope private val scope: CoroutineScope,
    // Providers keep the receive graph unbuilt until the gate first changes.
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

    /** One failing step must not end the collector, or the socket would never follow the gate again. */
    private inline fun safely(step: String, block: () -> Unit) {
        try {
            block()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            L.e { "[MigrationPause] event=receive_coordinator_failed step=$step ${e.stackTraceToString()}" }
        }
    }
}
