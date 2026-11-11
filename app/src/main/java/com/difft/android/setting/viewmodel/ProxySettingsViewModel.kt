package com.difft.android.setting.viewmodel

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.R
import com.difft.android.network.proxy.ProxyConfig
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.proxy.ProxyConnectivityChecker
import com.difft.android.network.proxy.ProxyLinkCodec
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.setting.proxy.ProxyE2eProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject









@HiltViewModel
class ProxySettingsViewModel @Inject constructor(
    private val proxyConfigProvider: ProxyConfigProvider,
    private val e2eProbe: ProxyE2eProbe,
    private val callState: OnGoingCallStateManager,
) : ViewModel() {

    











    sealed interface ProbeState {
        
        data object None : ProbeState
        
        data object Checking : ProbeState
        
        data object ProxyAvailable : ProbeState
        
        data object ServiceReachable : ProbeState
        
        data object ServiceUnreachable : ProbeState
        
        data class ProxyUnavailable(
            val verifyFailed: Boolean,
            val failure: ProxyConnectivityChecker.Failure,
        ) : ProbeState

        
        val isMain: Boolean get() = this != None && this != Checking

        
        val isSuccess: Boolean get() = this == ProxyAvailable || this == ServiceReachable
    }

    




    sealed interface UiEvent {
        
        data object Saved : UiEvent
        
        data object NeedPassphrase : UiEvent
        
        data object WrongPassphrase : UiEvent
        
        data class Toast(@StringRes val resId: Int) : UiEvent
    }

    data class UiState(
        val useProxy: Boolean = false,
        





        val protectCallIp: Boolean = false,
        val address: String = "",
        
        val isActive: Boolean = false,
        val savedAddress: String = "",
        val savedUseProxy: Boolean = false,
        
        val probe: ProbeState = ProbeState.None,
        



        val isSaving: Boolean = false,
        



        val readOnly: Boolean = false,
    ) {
        



        val hasChanges: Boolean
            get() = address.trim() != savedAddress.trim()

        






        val showNoTurnWarning: Boolean =
            ProxyConfig.parse(address.trim())?.turnEnabled() == false
    }

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private fun emitToast(@StringRes resId: Int) {
        _events.trySend(UiEvent.Toast(resId))
    }

    private var checkJob: Job? = null
    private var saveJob: Job? = null
    private var e2eJob: Job? = null

    
    @Volatile
    private var drainedHere = false 

    





    private val providerWritesPending: Boolean get() = queuedWrites.value > 0

    init {
        
        
        checkConnectivity(probeWhenDisabled = false)
        
        
        
        viewModelScope.launch {
            var sawQueued = false
            queuedWrites.collect { queued ->
                if (queued > 0) {
                    sawQueued = true
                    _uiState.update { it.copy(isSaving = true) }
                } else if (sawQueued) {
                    sawQueued = false
                    if (drainedHere) {
                        drainedHere = false
                    } else {
                        reloadWithoutProbe()
                        checkConnectivity(probeWhenDisabled = false)
                    }
                }
            }
        }
        
        viewModelScope.launch {
            callState.isInCalling.collect { inCall ->
                _uiState.update { it.copy(readOnly = inCall) }
            }
        }
    }

    













    fun onUseProxyChange(target: Boolean) {
        val state = _uiState.value
        if (state.readOnly) {
            
            
            emitToast(R.string.proxy_call_restricted_toast)
            return
        }
        if (target == state.useProxy) return
        if (!target) {
            
            retireProbesAndShow(useProxy = false)
            applyProviderWrite(write = { proxyConfigProvider.setEnabled(false) }, reload = ::reloadWithoutProbe)
            return
        }
        
        if (state.hasChanges || state.savedAddress.isBlank()) {
            emitToast(R.string.proxy_save_first)
            return
        }
        retireProbesAndShow(useProxy = true)
        applyProviderWrite(write = { proxyConfigProvider.setEnabled(true) }, reload = ::reloadAndProbe)
    }

    





    private fun applyProviderWrite(write: () -> Unit, reload: () -> Unit) {
        queuedWrites.update { it + 1 }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                providerWriteMutex.withLock {
                    var drainsQueue: Boolean
                    try {
                        withContext(Dispatchers.IO) { write() }
                    } finally {
                        
                        
                        
                        while (true) {
                            val current = queuedWrites.value
                            drainsQueue = current == 1
                            if (drainsQueue) drainedHere = true
                            if (queuedWrites.compareAndSet(current, current - 1)) break
                            if (drainsQueue) drainedHere = false
                        }
                    }
                    if (drainsQueue) reload()
                }
            }
        }
    }

    
    private fun retireProbesAndShow(useProxy: Boolean) {
        cancelProbes()
        _uiState.update { it.copy(useProxy = useProxy, probe = ProbeState.None) }
    }

    







    fun onProtectCallIpChange(target: Boolean) {
        val state = _uiState.value
        if (state.readOnly) {
            emitToast(R.string.proxy_call_restricted_toast)
            return
        }
        if (target == state.protectCallIp) return
        if (providerWritesPending) return 
        if (!state.useProxy) {
            emitToast(R.string.proxy_protect_call_enable_proxy_first)
            return
        }
        proxyConfigProvider.setProtectCallIp(target)
        _uiState.update { it.copy(protectCallIp = target) }
    }

    





    fun onAddressChange(value: String) {
        if (_uiState.value.readOnly) return 
        if (providerWritesPending) return 
        _uiState.update { it.copy(address = value, probe = ProbeState.None) }
    }

    











    fun save() {
        val state = _uiState.value
        if (state.readOnly) return 
        if (providerWritesPending) return 
        val raw = state.address.trim()
        if (raw.isEmpty() && state.savedAddress.isBlank()) {
            emitToast(R.string.proxy_address_empty)
            return
        }
        if (raw.isEmpty()) {
            
            
            
            retireProbesAndShow(useProxy = false)
            applyProviderWrite(write = { proxyConfigProvider.clear() }, reload = ::reloadWithoutProbe)
            return
        }
        val useProxy = state.useProxy
        launchSave {
            if (ProxyLinkCodec.isUnsupportedVersion(raw)) {
                return@launchSave UiEvent.Toast(R.string.proxy_unsupported_version)
            }
            when (ProxyLinkCodec.inspect(raw)) {
                
                
                
                ProxyLinkCodec.Mode.PLAIN ->
                    if (ProxyConfig.parse(raw) == null) {
                        UiEvent.Toast(R.string.proxy_invalid_address)
                    } else if (proxyConfigProvider.save(raw, useProxy)) {
                        UiEvent.Saved
                    } else {
                        UiEvent.Toast(R.string.proxy_invalid_address)
                    }

                ProxyLinkCodec.Mode.ENCRYPTED -> UiEvent.NeedPassphrase
                null -> UiEvent.Toast(R.string.proxy_invalid_address)
            }
        }
    }

    





    fun saveWithPassphrase(passphrase: String) {
        if (providerWritesPending) return 
        if (_uiState.value.readOnly) {
            
            
            emitToast(R.string.proxy_call_restricted_toast)
            return
        }
        val raw = _uiState.value.address.trim()
        val useProxy = _uiState.value.useProxy
        launchSave {
            when (val decoded = ProxyLinkCodec.decodeEncrypted(raw, passphrase)) {
                is ProxyLinkCodec.Decoded.Success -> {
                    proxyConfigProvider.save(decoded.config.toShareLink(), useProxy)
                    UiEvent.Saved
                }

                ProxyLinkCodec.Decoded.WrongPassphrase -> UiEvent.WrongPassphrase
                ProxyLinkCodec.Decoded.Invalid -> UiEvent.Toast(R.string.proxy_invalid_address)
            }
        }
    }

    





    private fun launchSave(block: suspend () -> UiEvent) {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            
            
            val event = providerWriteMutex.withLock { withContext(Dispatchers.IO) { block() } }
            if (event is UiEvent.Saved) {
                
                
                reloadAndProbe() 
            } else {
                _uiState.update { it.copy(isSaving = providerWritesPending) }
            }
            _events.send(event)
        }
    }

    
    private fun reloadAndProbe() {
        cancelProbes()
        _uiState.value = reloadedState() 
        checkConnectivity(probeWhenDisabled = true)
    }

    
    private fun reloadWithoutProbe() {
        cancelProbes()
        _uiState.value = reloadedState() 
    }

    





    private fun reloadedState(): UiState {
        val current = _uiState.value
        val fresh = loadState().copy(readOnly = current.readOnly)
        if (!providerWritesPending) return fresh
        return fresh.copy(useProxy = current.useProxy, isSaving = true)
    }

    fun refresh() = reloadAndProbe()

    private fun cancelProbes() {
        checkJob?.cancel()
        e2eJob?.cancel()
    }

    







    fun checkConnectivity(probeWhenDisabled: Boolean = true) {
        cancelProbes()
        val enabled = proxyConfigProvider.isEnabled 
        if (!enabled && !probeWhenDisabled) {
            _uiState.update { it.copy(probe = ProbeState.None) }
            return
        }
        val config = ProxyConfig.parse(_uiState.value.savedAddress.trim())
        if (config == null) {
            _uiState.update { it.copy(probe = ProbeState.None) }
            return
        }
        
        
        val snapshot = _uiState.value.savedAddress.trim()
        checkJob = viewModelScope.launch {
            _uiState.update { it.copy(probe = ProbeState.Checking) }
            val outcome = withContext(Dispatchers.IO) { ProxyConnectivityChecker.check(config) }
            if (!isProbeResultApplicable(snapshot)) return@launch
            if (!outcome.ok) {
                _uiState.update {
                    it.copy(
                        probe = ProbeState.ProxyUnavailable(
                            verifyFailed = outcome.failure == ProxyConnectivityChecker.Failure.PIN_MISMATCH,
                            failure = outcome.failure,
                        ),
                    )
                }
                return@launch
            }
            
            
            
            if (!proxyConfigProvider.isEnabled) {
                _uiState.update { it.copy(probe = ProbeState.ProxyAvailable) }
                return@launch
            }
            runE2eProbe(snapshot)
        }
    }

    








    private fun runE2eProbe(snapshot: String) {
        e2eJob?.cancel()
        e2eJob = viewModelScope.launch {
            val ok = try {
                e2eProbe.probe()
            } catch (cancellation: CancellationException) {
                
                
                
                throw cancellation
            } catch (_: Exception) {
                false
            }
            ensureActive()
            if (!isProbeResultApplicable(snapshot)) return@launch
            _uiState.update {
                it.copy(probe = if (ok) ProbeState.ServiceReachable else ProbeState.ServiceUnreachable)
            }
        }
    }

    








    private fun isProbeResultApplicable(snapshot: String): Boolean {
        val s = _uiState.value
        return s.savedAddress.trim() == snapshot && s.address.trim() == s.savedAddress.trim()
    }

    private fun loadState(): UiState {
        val saved = proxyConfigProvider.savedShareLink.orEmpty()
        val enabled = proxyConfigProvider.isEnabledByUser
        return UiState(
            useProxy = enabled,
            protectCallIp = proxyConfigProvider.isProtectCallIpEnabled,
            address = saved,
            isActive = proxyConfigProvider.isEnabled,
            savedAddress = saved,
            savedUseProxy = enabled,
        )
    }

    internal companion object {
        





        @VisibleForTesting
        internal val providerWriteMutex = Mutex()

        





        @VisibleForTesting
        internal val queuedWrites = MutableStateFlow(0)
    }
}
