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

    
    
    private val _events = MutableSharedFlow<CallSessionUiEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    
    
    private val preAttachLock = Any()
    private val preAttach = ArrayDeque<CallSessionUiEvent>()
    private var subscribed = false

    
    
    override val events: SharedFlow<CallSessionUiEvent> = _events.onSubscription { takePreAttach().forEach { emit(it) } }

    
    internal val hasSubscriber: Boolean get() = _events.subscriptionCount.value > 0

    
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

    



    internal fun setNetworkQuality(view: NetworkQualityView) {
        _networkQuality.value = view
    }

    
    internal fun setGroupCriticalAlert(enabled: Boolean) {
        _groupCriticalAlert.value = enabled
    }

    




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
