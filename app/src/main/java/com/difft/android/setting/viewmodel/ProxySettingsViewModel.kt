package com.difft.android.setting.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.network.proxy.ProxyConfig
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.proxy.ProxyConnectivityChecker
import com.difft.android.network.proxy.ProxyLinkCodec
import com.difft.android.setting.proxy.ProxyE2eProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the proxy settings screen. Wraps [ProxyConfigProvider], keeping the
 * editable form state (toggle + address) separate from the persisted state so
 * the Save button only activates when there is a pending change.
 *
 * After a save (or on open) the screen probes the configured endpoint via
 * [ProxyConnectivityChecker] and surfaces a reachable / unreachable status.
 */
@HiltViewModel
class ProxySettingsViewModel @Inject constructor(
    private val proxyConfigProvider: ProxyConfigProvider,
    private val e2eProbe: ProxyE2eProbe,
) : ViewModel() {

    /** Reachability status of the currently-saved proxy (stage 1: outer hop). */
    enum class ConnStatus { NONE, CHECKING, AVAILABLE, UNAVAILABLE }

    /**
     * Stage 2 end-to-end status: whether TempTalk is reachable *through* the
     * proxy. Orthogonal to [ConnStatus] — stage 1 = "proxy reachable", stage 2 =
     * "service reachable through proxy". Only runs after stage 1 == AVAILABLE.
     */
    enum class E2eStatus { NONE, CHECKING, OK, FAILED }

    /** Outcome of a Save action, driving toast / passphrase-dialog in the UI. */
    sealed interface SaveResult {
        /** Persisted successfully (enabled or disabled). */
        data object Saved : SaveResult
        /** Proxy is on but the entered address is not a valid link. */
        data object Invalid : SaveResult
        /** The link is passphrase-encrypted; the UI must collect a passphrase. */
        data object NeedPassphrase : SaveResult
        /** Passphrase did not decrypt the link. */
        data object WrongPassphrase : SaveResult
    }

    data class UiState(
        val useProxy: Boolean = false,
        val address: String = "",
        /** Proxy currently active = enabled AND the saved link parses. */
        val isActive: Boolean = false,
        val savedAddress: String = "",
        val savedUseProxy: Boolean = false,
        val connStatus: ConnStatus = ConnStatus.NONE,
        val connFailure: ProxyConnectivityChecker.Failure = ProxyConnectivityChecker.Failure.NONE,
        /** A save is in flight (e.g. PBKDF2 decrypt) — UI shows progress / blocks re-entry. */
        val isSaving: Boolean = false,
        /** Stage 2 status — orthogonal to [connStatus]; defaults to NONE on every fresh load. */
        val e2eStatus: E2eStatus = E2eStatus.NONE,
    ) {
        val hasChanges: Boolean
            get() = address.trim() != savedAddress.trim() || useProxy != savedUseProxy

        /**
         * The entered address is a valid proxy link but carries no TURN media relay.
         * Privacy mode makes TURN mandatory: such a proxy hides IM/signaling IP but
         * blocks calls (see proxy design §9.4), so the screen surfaces a warning.
         */
        val showNoTurnWarning: Boolean =
            useProxy && ProxyConfig.parse(address.trim())?.turnEnabled() == false
    }

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** One-shot save outcomes; the screen collects these to drive toasts / dialog. */
    private val _saveResults = Channel<SaveResult>(Channel.BUFFERED)
    val saveResults: Flow<SaveResult> = _saveResults.receiveAsFlow()

    private var checkJob: Job? = null
    private var saveJob: Job? = null
    private var e2eJob: Job? = null

    init {
        checkConnectivity()
    }

    fun onUseProxyChange(value: Boolean) = _uiState.update { it.copy(useProxy = value) }

    fun onAddressChange(value: String) = _uiState.update { it.copy(address = value) }

    /**
     * Persists the form off the main thread, emitting the outcome on [saveResults].
     * The provider only ever stores PLAIN links, so an encrypted link must be
     * decrypted first ([SaveResult.NeedPassphrase] → [saveWithPassphrase]).
     * Disabling or saving a plain/blank link persists directly.
     */
    fun save() {
        val raw = _uiState.value.address.trim()
        val useProxy = _uiState.value.useProxy
        launchSave {
            // Turning the proxy off (or saving while off): keep the entered text as
            // display only; routing is inactive so no decode/passphrase is needed.
            if (!useProxy) {
                proxyConfigProvider.save(raw, false)
                return@launchSave SaveResult.Saved
            }
            if (raw.isEmpty()) return@launchSave SaveResult.Invalid

            when (ProxyLinkCodec.inspect(raw)) {
                ProxyLinkCodec.Mode.PLAIN ->
                    if (proxyConfigProvider.save(raw, true)) SaveResult.Saved else SaveResult.Invalid

                ProxyLinkCodec.Mode.ENCRYPTED -> SaveResult.NeedPassphrase
                null -> SaveResult.Invalid
            }
        }
    }

    /**
     * Completes saving an encrypted link: decrypts with [passphrase] (PBKDF2 —
     * deliberately expensive, so it runs off the main thread), then persists the
     * decrypted config as a PLAIN link so it survives restarts without re-prompting.
     */
    fun saveWithPassphrase(passphrase: String) {
        val raw = _uiState.value.address.trim()
        val useProxy = _uiState.value.useProxy
        launchSave {
            when (val decoded = ProxyLinkCodec.decodeEncrypted(raw, passphrase)) {
                is ProxyLinkCodec.Decoded.Success -> {
                    proxyConfigProvider.save(decoded.config.toShareLink(), useProxy)
                    SaveResult.Saved
                }

                ProxyLinkCodec.Decoded.WrongPassphrase -> SaveResult.WrongPassphrase
                ProxyLinkCodec.Decoded.Invalid -> SaveResult.Invalid
            }
        }
    }

    /**
     * Runs [block] (decode + persist; may be CPU/IO heavy) on [Dispatchers.IO]
     * with an `isSaving` guard, then reloads on success and emits the outcome.
     * Ignores re-entry while a save is already in flight.
     */
    private fun launchSave(block: suspend () -> SaveResult) {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val result = withContext(Dispatchers.IO) { block() }
            if (result is SaveResult.Saved) {
                reload() // rebuilds UiState (clears isSaving) + re-probes connectivity
            } else {
                _uiState.update { it.copy(isSaving = false) }
            }
            _saveResults.send(result)
        }
    }

    private fun reload() {
        // Stop any in-flight stage 2 BEFORE rebuilding state, so a stale e2eJob
        // can't write e2eStatus after loadState() resets it to NONE (GAP-8).
        e2eJob?.cancel()
        _uiState.value = loadState() // loadState() defaults e2eStatus = NONE
        checkConnectivity()
    }

    fun refresh() = reload()

    /**
     * Probes the saved proxy endpoint off the main thread. Only runs when the
     * proxy is enabled and the saved link is valid; otherwise clears the status.
     */
    fun checkConnectivity() {
        val config = proxyConfigProvider.current
        // Any re-check first cancels stage 2 so a stale e2eJob can't write e2eStatus.
        e2eJob?.cancel()
        if (config == null) {
            checkJob?.cancel()
            _uiState.update {
                it.copy(
                    connStatus = ConnStatus.NONE,
                    connFailure = ProxyConnectivityChecker.Failure.NONE,
                    e2eStatus = E2eStatus.NONE,
                )
            }
            return
        }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connStatus = ConnStatus.CHECKING,
                    connFailure = ProxyConnectivityChecker.Failure.NONE,
                    e2eStatus = E2eStatus.NONE,
                )
            }
            val outcome = withContext(Dispatchers.IO) { ProxyConnectivityChecker.check(config) }
            if (!outcome.ok) {
                // Stage 1 failed → do NOT trigger stage 2; e2eStatus stays NONE.
                _uiState.update {
                    it.copy(
                        connStatus = ConnStatus.UNAVAILABLE,
                        connFailure = outcome.failure,
                    )
                }
                return@launch
            }
            // Stage 1 OK. Re-check the proxy is STILL enabled before stage 2: the
            // user may have toggled OFF + Saved mid-probe, clearing provider.current.
            // The stale snapshot still passed stage 1, but the proxy is now disabled
            // — don't run stage 2 (would show double-green while proxy is off).
            if (proxyConfigProvider.current == null) {
                _uiState.update {
                    it.copy(
                        connStatus = ConnStatus.AVAILABLE,
                        connFailure = outcome.failure,
                        e2eStatus = E2eStatus.NONE,
                    )
                }
                return@launch
            }
            // Merge AVAILABLE + e2eStatus=CHECKING into ONE update to avoid a 1-frame
            // flicker (the stage 2 row appearing/disappearing between two updates).
            _uiState.update {
                it.copy(
                    connStatus = ConnStatus.AVAILABLE,
                    connFailure = outcome.failure,
                    e2eStatus = E2eStatus.CHECKING,
                )
            }
            runE2eProbe()
        }
    }

    /**
     * Stage 2: runs serially after stage 1 == AVAILABLE; own [e2eJob] guards re-entry.
     * The caller ([checkConnectivity]) already set e2eStatus = CHECKING, so this does
     * NOT set it again (avoids a flicker). [runCatching] is defense-in-depth: the
     * probe contract is "never throws", but the VM doesn't rely on that discipline.
     */
    private fun runE2eProbe() {
        e2eJob?.cancel()
        e2eJob = viewModelScope.launch {
            val ok = runCatching { e2eProbe.probe() }.getOrDefault(false)
            _uiState.update { it.copy(e2eStatus = if (ok) E2eStatus.OK else E2eStatus.FAILED) }
        }
    }

    private fun loadState(): UiState {
        val saved = proxyConfigProvider.savedShareLink.orEmpty()
        val enabled = proxyConfigProvider.isEnabledByUser
        return UiState(
            useProxy = enabled,
            address = saved,
            isActive = proxyConfigProvider.isEnabled,
            savedAddress = saved,
            savedUseProxy = enabled,
        )
    }
}
