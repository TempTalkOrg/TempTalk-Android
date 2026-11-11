package com.difft.android.linkeddevices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject








@HiltViewModel
class LinkedDevicesViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val countStore: LinkedDevicesCountStore,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val hasLoadedOnce: Boolean = false, 
        val devices: List<DeviceUiState> = emptyList(),
    )

    sealed interface UiEvent {
        data object UnlinkFailed : UiEvent

        
        data object FetchFailed : UiEvent
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    
    
    private var expectingMoreDevices = false
    private var baselineDeviceCount = 0
    private var pollTickCount = 0
    private var pollJob: Job? = null

    





    fun refresh(force: Boolean = false, silent: Boolean = false): Job? {
        val previous = loadJob
        if (previous?.isActive == true && !force) return previous
        
        
        
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _uiState.update { it.copy(isLoading = !it.hasLoadedOnce) }
            try {
                val devices = deviceRepository.getDevices().map { it.toUiState() }
                countStore.update(devices.size) 
                L.i { "[LinkedDevices] refresh: loaded count=${devices.size}" }
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, hasLoadedOnce = true, devices = devices)
                }
                onDevicesLoaded(devices.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthorizationFailedException) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, hasLoadedOnce = true) }
                L.i { "[LinkedDevices] refresh: auth failed code=${e.code}" }
                clearExpecting() 
            } catch (e: Exception) {
                
                
                
                L.w { "[LinkedDevices] refresh: failed silent=$silent: ${e.stackTraceToString()}" }
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, hasLoadedOnce = true) }
                if (!silent) _events.trySend(UiEvent.FetchFailed)
                
                if (expectingMoreDevices) startExpectPollIfNeeded()
            }
        }
        loadJob = next
        previous?.cancel()
        next.start()
        return next
    }

    
    fun pullRefresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        refresh(force = true)
    }

    




    fun onLinkNewDeviceClicked() {
        baselineDeviceCount = _uiState.value.devices.size
        expectingMoreDevices = true
        pollTickCount = 0
        L.i { "[LinkedDevices] expect-more: armed baseline=$baselineDeviceCount" }
    }

    
    fun stopExpecting() {
        pollJob?.cancel()
        pollJob = null
    }

    
    private fun onDevicesLoaded(count: Int) {
        if (!expectingMoreDevices) return
        if (count > baselineDeviceCount) {
            L.i { "[LinkedDevices] expect-more: satisfied count=$count baseline=$baselineDeviceCount" }
            clearExpecting()
        } else {
            
            if (count < baselineDeviceCount) baselineDeviceCount = count
            startExpectPollIfNeeded()
        }
    }

    
    private fun startExpectPollIfNeeded() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (expectingMoreDevices) {
                delay(EXPECT_POLL_INTERVAL_MS)
                if (!expectingMoreDevices) break
                if (pollTickCount >= MAX_EXPECT_POLL_TICKS) {
                    L.i { "[LinkedDevices] expect-more: tick limit reached, giving up" }
                    clearExpecting()
                    break
                }
                pollTickCount++
                refresh(force = true, silent = true)
            }
        }
    }

    private fun clearExpecting() {
        expectingMoreDevices = false
        pollJob?.cancel()
        pollJob = null
    }

    
    fun unlink(deviceId: Int) {
        if (_uiState.value.devices.firstOrNull { it.id == deviceId }?.isUnlinking == true) return
        setUnlinking(deviceId, true)
        viewModelScope.launch {
            try {
                deviceRepository.removeDevice(deviceId)
                L.i { "[LinkedDevices] unlink: success id=$deviceId" }
                
                
                
                
                
                
                refresh(force = true)
                while (true) {
                    val attempt = loadJob ?: break
                    attempt.join()
                    if (loadJob === attempt) break
                }
                setUnlinking(deviceId, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthorizationFailedException) {
                L.i { "[LinkedDevices] unlink id=$deviceId: auth failed code=${e.code}" }
                setUnlinking(deviceId, false)
            } catch (e: Exception) {
                L.w { "[LinkedDevices] unlink: failed id=$deviceId: ${e.stackTraceToString()}" }
                setUnlinking(deviceId, false) 
                _events.trySend(UiEvent.UnlinkFailed)
            }
        }
    }

    private fun setUnlinking(deviceId: Int, value: Boolean) =
        _uiState.update { s ->
            s.copy(devices = s.devices.map { if (it.id == deviceId) it.copy(isUnlinking = value) else it })
        }

    
    private fun DeviceInfo.toUiState() = DeviceUiState(
        id = id,
        rawName = name,
        created = created,
        lastActive = if (lastSeen <= created) created else lastSeen,
    )

    private companion object {
        const val EXPECT_POLL_INTERVAL_MS = 10_000L 
        const val MAX_EXPECT_POLL_TICKS = 18        
    }
}
