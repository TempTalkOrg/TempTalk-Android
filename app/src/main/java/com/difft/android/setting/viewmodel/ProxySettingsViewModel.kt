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
    private val callState: OnGoingCallStateManager,
) : ViewModel() {

    /**
     * The SINGLE main status shown in the status area (proxy design §6). The
     * two-stage probe (stage 1 = proxy reachable, stage 2 = TempTalk reachable
     * *through* the proxy) is collapsed into one mutually-exclusive status by
     * priority: [ServiceReachable] > [ServiceUnreachable] > [ProxyAvailable] >
     * [ProxyUnavailable]. Only one is ever displayed.
     *
     * - Switch OFF: a save/recheck probes stage 1 only → [ProxyAvailable] /
     *   [ProxyUnavailable] (no business status, §6.1).
     * - Switch ON: stage 1 OK keeps [Checking] (single loading) until stage 2
     *   resolves → [ServiceReachable] / [ServiceUnreachable].
     */
    sealed interface ProbeState {
        /** Nothing to show (no saved address, off-on-enter, or editing). */
        data object None : ProbeState
        /** A probe (stage 1 or stage 2) is in flight — show a small loading only. */
        data object Checking : ProbeState
        /** Stage 1 OK while the switch is OFF: "代理可用" (green). */
        data object ProxyAvailable : ProbeState
        /** Stage 2 OK: "已通过代理连接到 Quicall" (green). */
        data object ServiceReachable : ProbeState
        /** Stage 1 OK but stage 2 failed: "无法通过代理连接到 Quicall" (red). */
        data object ServiceUnreachable : ProbeState
        /** Stage 1 failed: "代理不可用" / (pin) "无法验证代理" (red). */
        data class ProxyUnavailable(
            val verifyFailed: Boolean,
            val failure: ProxyConnectivityChecker.Failure,
        ) : ProbeState

        /** A settled main status — eligible for the "recheck" chip (§6). */
        val isMain: Boolean get() = this != None && this != Checking

        /** Success-colored statuses render green; the rest render red. */
        val isSuccess: Boolean get() = this == ProxyAvailable || this == ServiceReachable
    }

    /**
     * One-shot UI side effect emitted on [events]. Covers BOTH save outcomes and
     * standalone toasts (e.g. the toggle-validation "save address first" prompt),
     * so the screen has a single event stream to collect.
     */
    sealed interface UiEvent {
        /** Persisted successfully (address saved, or deletion handled inline). */
        data object Saved : UiEvent
        /** The link is passphrase-encrypted; the UI must collect a passphrase. */
        data object NeedPassphrase : UiEvent
        /** Passphrase did not decrypt the link; keep the dialog open and toast. */
        data object WrongPassphrase : UiEvent
        /** Show a transient toast (invalid address, empty input, unsupported version, save-first). */
        data class Toast(@StringRes val resId: Int) : UiEvent
    }

    data class UiState(
        val useProxy: Boolean = false,
        /**
         * "Protect IP address in calls" intent. Only operable while [useProxy] is ON
         * (the switch is greyed and shows a toast otherwise). When ON the call/meeting
         * plane routes through the proxy; when OFF calls connect directly even with the
         * proxy active for IM.
         */
        val protectCallIp: Boolean = false,
        val address: String = "",
        /** Proxy currently active = enabled AND the saved link parses. */
        val isActive: Boolean = false,
        val savedAddress: String = "",
        val savedUseProxy: Boolean = false,
        /** The single main status shown in the status area; defaults to NONE on every fresh load. */
        val probe: ProbeState = ProbeState.None,
        /**
         * A save is in flight (e.g. PBKDF2 decrypt), or switch / delete writes are queued (#1400):
         * the UI shows progress and blocks re-entry.
         */
        val isSaving: Boolean = false,
        /**
         * A call is in progress: the whole screen is view-only — no toggle, edit,
         * save, delete or recheck (proxy design §7 / §8). Driven by the call state.
         */
        val readOnly: Boolean = false,
    ) {
        /**
         * Save-button enablement key. Per proxy design §5 this tracks the ADDRESS
         * only — toggling the proxy switch must NOT light up "Save address", since
         * the switch is an independent, immediately-applied action ([onUseProxyChange]).
         */
        val hasChanges: Boolean
            get() = address.trim() != savedAddress.trim()

        /**
         * The entered address is a valid proxy link but carries no TURN media relay.
         * Privacy mode makes TURN mandatory: such a proxy hides IM/signaling IP but
         * blocks calls (proxy design §8). This "call restriction" hint is derived by
         * LOCAL parse of the current input only — independent of the switch and of any
         * network probe (§6) — so it shows as soon as a no-TURN address parses.
         */
        val showNoTurnWarning: Boolean =
            ProxyConfig.parse(address.trim())?.turnEnabled() == false
    }

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** One-shot UI side effects; the screen collects these to drive toasts / dialog. */
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private fun emitToast(@StringRes resId: Int) {
        _events.trySend(UiEvent.Toast(resId))
    }

    private var checkJob: Job? = null
    private var saveJob: Job? = null
    private var e2eJob: Job? = null

    /** True when this screen's own write emptied [queuedWrites]; it reloads itself then. */
    @Volatile
    private var drainedHere = false // set on the writing thread, read by the collector

    /**
     * While switch / delete writes are queued by any proxy screen, address edits and saves
     * are ignored and the save button is disabled: the form is rebuilt from the provider
     * when the last write lands, so there is no draft for that reload to overwrite. Writes
     * take milliseconds.
     */
    private val providerWritesPending: Boolean get() = queuedWrites.value > 0

    init {
        // On enter, only auto-probe when the proxy is ON with a saved, unedited
        // address (§6). A switch-OFF screen does not auto-probe.
        checkConnectivity(probeWhenDisabled = false)
        // Writes another proxy screen still has queued (one the user left, #1400): hold the
        // form meanwhile, and reload once the last one lands. That reload retires the enter
        // probe, so the enter rule above runs again on the state those writes stored.
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
        // A call in progress makes the whole screen view-only (§7 / §8).
        viewModelScope.launch {
            callState.isInCalling.collect { inCall ->
                _uiState.update { it.copy(readOnly = inCall) }
            }
        }
    }

    /**
     * Toggles the proxy switch as an INDEPENDENT, immediately-applied action
     * (proxy design §2.2 / §3) — it does NOT go through "Save address".
     *
     * - OFF: disables routing right away via [ProxyConfigProvider.setEnabled];
     *   the saved address is preserved.
     * - ON: only enables when there is a saved address AND no unsaved edits;
     *   otherwise it prompts the user to save first and leaves the switch OFF
     *   (the UI binds `checked` to [UiState.useProxy], so it visually reverts).
     *
     * The provider write runs off the main thread (it evicts pooled connections, whose
     * TLS close throws on main, #1400). The switch state flips optimistically so a quick
     * second tap is judged against the first, and the form is reloaded from the provider
     * once the write completes.
     */
    fun onUseProxyChange(target: Boolean) {
        val state = _uiState.value
        if (state.readOnly) {
            // During a call the proxy can't be toggled (§8). The switch keeps showing
            // the real state (not greyed out); a tap surfaces a toast instead.
            emitToast(R.string.proxy_call_restricted_toast)
            return
        }
        if (target == state.useProxy) return
        if (!target) {
            // Turning OFF does not probe (§6 "不触发探测的场景"); clear the status.
            retireProbesAndShow(useProxy = false)
            applyProviderWrite(write = { proxyConfigProvider.setEnabled(false) }, reload = ::reloadWithoutProbe)
            return
        }
        // Enabling: require a saved, unedited address before routing turns on.
        if (state.hasChanges || state.savedAddress.isBlank()) {
            emitToast(R.string.proxy_save_first)
            return
        }
        retireProbesAndShow(useProxy = true)
        applyProviderWrite(write = { proxyConfigProvider.setEnabled(true) }, reload = ::reloadAndProbe)
    }

    /**
     * Runs [write] on [Dispatchers.IO] under [providerWriteMutex], then [reload] on the
     * view model scope. NonCancellable so leaving the screen right after a tap still
     * persists the toggle, as the former synchronous call did. The write that empties
     * the process-wide queue reloads, so a superseded tap cannot flash its intermediate state.
     */
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
                        // Atomic against other screens' writes (the finally may run on an IO
                        // thread under test dispatchers). drainedHere is set before the CAS
                        // that publishes 0: a collector may run inline inside that publish.
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

    /** Retires in-flight probes and reflects the tapped switch value before the write lands. */
    private fun retireProbesAndShow(useProxy: Boolean) {
        cancelProbes()
        _uiState.update { it.copy(useProxy = useProxy, probe = ProbeState.None) }
    }

    /**
     * Toggles "Protect IP address in calls". Only operable while the proxy is ON:
     *  - During a call the screen is view-only — surface the standard restriction toast.
     *  - When the proxy is OFF the switch is greyed; a tap prompts to enable the proxy
     *    first and leaves the value unchanged (the UI binds to [UiState.protectCallIp]).
     *  - Otherwise persist immediately via [ProxyConfigProvider.setProtectCallIp]; the
     *    call plane reads it on the next call connection. No probe/status change.
     */
    fun onProtectCallIpChange(target: Boolean) {
        val state = _uiState.value
        if (state.readOnly) {
            emitToast(R.string.proxy_call_restricted_toast)
            return
        }
        if (target == state.protectCallIp) return
        if (providerWritesPending) return // judged against the switch once its write lands (#1400)
        if (!state.useProxy) {
            emitToast(R.string.proxy_protect_call_enable_proxy_first)
            return
        }
        proxyConfigProvider.setProtectCallIp(target)
        _uiState.update { it.copy(protectCallIp = target) }
    }

    /**
     * Editing the address invalidates any shown probe status (§6.0.1): clear it so
     * a stale result can't sit under a freshly-edited address. The in-flight probe
     * is left running but its result is dropped by the snapshot guard
     * ([isProbeResultApplicable]).
     */
    fun onAddressChange(value: String) {
        if (_uiState.value.readOnly) return // view-only during a call (§7)
        if (providerWritesPending) return // the form reloads when the queued writes land (#1400)
        _uiState.update { it.copy(address = value, probe = ProbeState.None) }
    }

    /**
     * "Save address" action (proxy design §5). Validates and persists the ADDRESS
     * only — it never flips the proxy switch (that is [onUseProxyChange]). Order:
     *  1. empty + no saved address → toast "enter address".
     *  2. empty + has saved address → delete saved address, disable proxy (no probe).
     *  3. unsupported version → toast.
     *  4. malformed → toast "invalid address".
     *  5. encrypted → prompt for passphrase ([saveWithPassphrase]).
     *  6. plain & parseable → persist (keeping the current switch state); a probe
     *     follows in [reload]. Whether it affects the entry status depends on the
     *     switch, which the entry page reads from the provider.
     */
    fun save() {
        val state = _uiState.value
        if (state.readOnly) return // view-only during a call (§7)
        if (providerWritesPending) return // the form reloads when the queued writes land (#1400)
        val raw = state.address.trim()
        if (raw.isEmpty() && state.savedAddress.isBlank()) {
            emitToast(R.string.proxy_address_empty)
            return
        }
        if (raw.isEmpty()) {
            // Clearing the input and saving = delete the saved address: disable the
            // proxy, clear status, no probe (§2.4). clear() persists link=null+off.
            // clear() evicts pooled connections, so it runs off-main like the toggle (#1400).
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
                // Validate the payload parses BEFORE persisting: provider.save()
                // skips parsing when disabled, so a switch-off save could otherwise
                // store an unparseable plain link.
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

    /**
     * Completes saving an encrypted link: decrypts with [passphrase] (PBKDF2 —
     * deliberately expensive, so it runs off the main thread), then persists the
     * decrypted config as a PLAIN link so it survives restarts without re-prompting.
     * Keeps the current switch state (save never toggles the proxy).
     */
    fun saveWithPassphrase(passphrase: String) {
        if (providerWritesPending) return // the form reloads when the queued writes land (#1400)
        if (_uiState.value.readOnly) {
            // A call may have started while the passphrase dialog was open. Block the
            // persist (§7/§8) and toast; the Toast event also dismisses the dialog.
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

    /**
     * Runs [block] (decode + persist; may be CPU/IO heavy) on [Dispatchers.IO]
     * with an `isSaving` guard, then reloads on success and emits the outcome.
     * Ignores re-entry while a save is already in flight. The persist runs under
     * [providerWriteMutex], after any switch / delete write queued before it (#1400).
     */
    private fun launchSave(block: suspend () -> UiEvent) {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            // Same order as the switch / delete writes, so a queued delete cannot land
            // after (and erase) an address saved later (#1400).
            val event = providerWriteMutex.withLock { withContext(Dispatchers.IO) { block() } }
            if (event is UiEvent.Saved) {
                // Saving a valid address probes even while the switch is OFF, so the
                // status area can show "代理可用" (§2.1 / §6.1).
                reloadAndProbe() // rebuilds UiState (clears isSaving) + re-probes
            } else {
                _uiState.update { it.copy(isSaving = providerWritesPending) }
            }
            _events.send(event)
        }
    }

    /** Rebuilds the form state from the provider and re-probes the saved address. */
    private fun reloadAndProbe() {
        cancelProbes()
        _uiState.value = reloadedState() // probe defaults to NONE
        checkConnectivity(probeWhenDisabled = true)
    }

    /** Rebuilds the form state and clears the status WITHOUT probing (off / delete). */
    private fun reloadWithoutProbe() {
        cancelProbes()
        _uiState.value = reloadedState() // probe defaults to NONE
    }

    /**
     * The provider's state, keeping the call restriction (only its collector changes it). A
     * reload that lands while switch / delete writes are still queued (an address save that
     * finished first) also keeps the tapped switch value and the busy state, so the switch
     * does not flash back and the form stays held until the last write reloads it (#1400).
     */
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

    /**
     * Probes the SAVED proxy address off the main thread and collapses the two
     * stages into one [ProbeState] main status (§6).
     *
     * @param probeWhenDisabled when false, skips probing if the switch is OFF
     *   (screen-enter, §6); when true, probes the saved address even while OFF to
     *   surface "代理可用" (after Save, and on manual recheck).
     */
    fun checkConnectivity(probeWhenDisabled: Boolean = true) {
        cancelProbes()
        val enabled = proxyConfigProvider.isEnabled // switch ON AND saved link valid
        if (!enabled && !probeWhenDisabled) {
            _uiState.update { it.copy(probe = ProbeState.None) }
            return
        }
        val config = ProxyConfig.parse(_uiState.value.savedAddress.trim())
        if (config == null) {
            _uiState.update { it.copy(probe = ProbeState.None) }
            return
        }
        // Snapshot the address this probe is for; a result is dropped if the saved
        // address changed or the input was edited before it returns (§6.0.1).
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
            // Stage 1 OK. If the proxy is OFF (no business status, §6.1) — including
            // a toggle-OFF that landed mid-probe — settle on "代理可用" and skip
            // stage 2. Otherwise stay in CHECKING (single loading, §6.0) for stage 2.
            if (!proxyConfigProvider.isEnabled) {
                _uiState.update { it.copy(probe = ProbeState.ProxyAvailable) }
                return@launch
            }
            runE2eProbe(snapshot)
        }
    }

    /**
     * Stage 2: whether TempTalk is reachable *through* the proxy. Runs after stage
     * 1 == OK while the switch is ON. The result is dropped if [snapshot] is stale.
     *
     * A RETIRED probe must never settle the status area. [cancelProbes] retires one
     * on every toggle / save / recheck, but [isProbeResultApplicable] cannot detect
     * that: the saved address is usually unchanged, so a retired run's verdict still
     * matches. Cancellation is the only signal, and it needs both guards below —
     * turning the switch OFF while stage 2 is in flight otherwise repaints the red
     * "无法通过代理连接到 Quicall" plus its recheck icon under an already-off switch,
     * because the verdict hops back to Main only after [reloadWithoutProbe] has
     * emptied the status area.
     *
     * The catch is defense-in-depth for a probe that breaks its "never throws"
     * contract, but it must not swallow [CancellationException] — [ProxyE2eProbe]
     * deliberately rethrows that instead of answering `false` (see
     * `ProxyE2eProbeImpl.probeOnce`) precisely so a cancelled probe is not read as
     * "unreachable". [ensureActive] then covers the mirror case, where the probe
     * produced a real verdict before the cancellation reached it.
     */
    private fun runE2eProbe(snapshot: String) {
        e2eJob?.cancel()
        e2eJob = viewModelScope.launch {
            val ok = try {
                e2eProbe.probe()
            } catch (cancellation: CancellationException) {
                // MUST stay ahead of the Exception clause: CancellationException IS-A
                // Exception, so merging or reordering the two silently turns every
                // cancelled probe back into a "服务不可达" verdict.
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

    /**
     * A probe result applies only when the saved address is still [snapshot] AND
     * the input has no unsaved edits — otherwise the user has moved on and the
     * stale result must not overwrite the cleared status (§6.0.1).
     *
     * This tracks EDITS only. It deliberately says nothing about whether the probe
     * was retired — editing the address leaves the in-flight probe running, so the
     * two conditions are independent; see [runE2eProbe] for the cancellation half.
     */
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
        /**
         * Serialises the provider writes that run off-main (#1400): taps are applied in the
         * order they were made and none is dropped. Process-wide rather than per screen: a
         * write still queued by a screen the user already left (it is NonCancellable) must
         * land before, not after, what a reopened screen writes. Mutex is FIFO-fair.
         */
        @VisibleForTesting
        internal val providerWriteMutex = Mutex()

        /**
         * Provider writes queued by any proxy screen and not yet landed. Every change is
         * atomic ([update] / compareAndSet), so it is safe from any thread. Every screen holds
         * its form while it is above zero and reloads when it returns to zero, so a screen
         * reopened during a left screen's queue is not left stale.
         */
        @VisibleForTesting
        internal val queuedWrites = MutableStateFlow(0)
    }
}
