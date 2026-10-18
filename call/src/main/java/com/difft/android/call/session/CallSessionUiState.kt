package com.difft.android.call.session

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.network.NetworkQualityView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update

/**
 * The session's UI output: writer side of [CallSessionUiSource]. Pure Kotlin, no Android types, and
 * default-constructible so render-layer tests can drive a bare UI controller over it.
 *
 * The five state fields have one writer each, all session-graph code on main (the room-event
 * collector, the speaking watchdog, the countdown tick, `NetworkQualityCoordinator`).
 * [setGroupCriticalAlert] and [emit] may be called from any thread.
 */
class CallSessionUiState : CallSessionUiSource {
    private val _speakingEnabled = MutableStateFlow(true)
    override val speakingEnabled = _speakingEnabled.asStateFlow()

    private val _isShareScreening = MutableStateFlow(false)
    override val isShareScreening = _isShareScreening.asStateFlow()

    private val _countDownDurationStr = MutableStateFlow("00:00")
    override val countDownDurationStr = _countDownDurationStr.asStateFlow()

    private val _reconnectCount = MutableStateFlow(0)
    override val reconnectCount = _reconnectCount.asStateFlow()

    private val _networkQuality = MutableStateFlow(NetworkQualityView.NONE)
    override val networkQuality = _networkQuality.asStateFlow()

    private val _groupCriticalAlert = MutableStateFlow(false)
    override val groupCriticalAlert = _groupCriticalAlert.asStateFlow()

    // Same capacity and overflow policy as the UI controller's barrage flows these events feed:
    // tryEmit never suspends (emitters run inside the main room-event collector) and never fails.
    private val _events = MutableSharedFlow<CallSessionUiEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Guarded by [preAttachLock]: events emitted before the first subscriber, oldest dropped past
    // EVENT_BUFFER, and whether that subscriber has come.
    private val preAttachLock = Any()
    private val preAttach = ArrayDeque<CallSessionUiEvent>()
    private var subscribed = false

    // The drain runs once the subscriber is registered, so an event emitted meanwhile is either
    // drained or delivered after the drained ones: order is kept.
    override val events: SharedFlow<CallSessionUiEvent> = _events.onSubscription { takePreAttach().forEach { emit(it) } }

    /** Whether a call page collects [events] right now. */
    internal val hasSubscriber: Boolean get() = _events.subscriptionCount.value > 0

    // ---- main-confined writers ----
    internal fun setSpeakingEnabled(enabled: Boolean) {
        _speakingEnabled.value = enabled
    }

    internal fun setShareScreening(enabled: Boolean) {
        _isShareScreening.value = enabled
    }

    internal fun setCountDownDurationStr(str: String) {
        _countDownDurationStr.value = str
    }

    internal fun incrementReconnectCount() {
        _reconnectCount.update { it + 1 }
    }

    /**
     * Publishes a whole new weak-network snapshot. A full replacement, so plain assignment is
     * correct; `MutableStateFlow` drops equal values, which de-duplicates the 500 ms tick.
     */
    internal fun setNetworkQuality(view: NetworkQualityView) {
        _networkQuality.value = view
    }

    // ---- any-thread writers ----
    internal fun setGroupCriticalAlert(enabled: Boolean) {
        _groupCriticalAlert.value = enabled
    }

    /**
     * Never suspends, never fails (`DROP_OLDEST`). Callable from main, Default or IO. Until the first
     * subscriber an event is kept for it (toasts excepted: a toast is only for a page on screen);
     * after that an event with no subscriber is dropped.
     */
    internal fun emit(event: CallSessionUiEvent) {
        if (event is CallSessionUiEvent.Toast) {
            if (!hasSubscriber) L.w { "[Call][SessionUi] toast dropped: no ui attached" }
        } else {
            synchronized(preAttachLock) {
                if (!subscribed) {
                    if (preAttach.size == EVENT_BUFFER) preAttach.removeFirst()
                    preAttach.addLast(event)
                    return
                }
            }
        }
        _events.tryEmit(event)
    }

    private fun takePreAttach(): List<CallSessionUiEvent> = synchronized(preAttachLock) {
        subscribed = true
        preAttach.toList().also { preAttach.clear() }
    }

    private companion object {
        const val EVENT_BUFFER = 64
    }
}
