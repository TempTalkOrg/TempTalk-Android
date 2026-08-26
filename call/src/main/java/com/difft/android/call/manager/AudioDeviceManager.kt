package com.difft.android.call.manager

import android.content.Context
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.call.BuildConfig
import com.difft.android.call.data.VoicePreset
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet

/**
 * Owns the audio-route truth for a call.
 *
 * [AudioRouteSnapshot.confirmed] asserts "audio is coming out of this device right now" — written
 * only from an observed fact ([onRouteConfirmed]) or a disproof rule, never from "request
 * delivered" or the library's `selectedAudioDevice` (assigned when routing *starts*).
 *
 * Does not drive the platform: [select] only advances the state machine to `Applying`; whoever
 * collects [pendingRoute] drives and reports back what it observed. This makes "cancel the
 * previous attempt when the target changes" a structural property of `collectLatest`.
 */
class AudioDeviceManager(
    private val context: Context,
    private val callType: String,
    private val userManager: UserManager,
) : AudioRouteHost {
    private val _deNoiseEnable = MutableStateFlow(true)
    val deNoiseEnable = _deNoiseEnable.asStateFlow()

    private val _deNoiseMode = MutableStateFlow(AudioModule.RNNOISE)
    val deNoiseMode: StateFlow<AudioModule> = _deNoiseMode.asStateFlow()

    private val _voicePreset = MutableStateFlow(VoicePreset.ORIGINAL)
    val voicePreset: StateFlow<VoicePreset> = _voicePreset.asStateFlow()

    val audioHandler by lazy {
        AudioSwitchHandler(context).apply {
            loggingEnabled = BuildConfig.DEBUG
            preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
            ) + if(callType == CallType.ONE_ON_ONE.type) {
                listOf(
                    AudioDevice.Earpiece::class.java,
                    AudioDevice.Speakerphone::class.java
                )
            } else {
                listOf(
                    AudioDevice.Speakerphone::class.java,
                    AudioDevice.Earpiece::class.java
                )
            }
        }
    }

    /**
     * The single source of route truth. Starts empty and `Idle`: the library's
     * `selectedAudioDevice` is null at construction, and even when it is not, it means "routing
     * started", not "routing is active".
     */
    private val _routeSnapshot = MutableStateFlow(AudioRouteSnapshot())
    override val routeSnapshot: StateFlow<AudioRouteSnapshot> = _routeSnapshot.asStateFlow()

    val requested: AudioDevice? get() = routeSnapshot.value.requested
    val confirmed: AudioDevice? get() = routeSnapshot.value.confirmed
    val routeState: AudioRouteState get() = routeSnapshot.value.state

    /**
     * `distinctUntilChanged` runs on the whole state, not on the filtered `Applying` values, so
     * an `Applying(D) -> Idle -> Applying(D)` sequence re-emits: the interruption is exactly the
     * signal that the previous attempt was abandoned and a fresh one must start.
     */
    override val pendingRoute: Flow<AudioRouteState.Applying> = routeSnapshot
        .map { it.state }
        .distinctUntilChanged()
        .filterIsInstance<AudioRouteState.Applying>()

    /** Consumed by the route lifecycle guard as its "AudioSwitch is ready" edge. */
    val availableDevices: Flow<List<AudioDevice>> = routeSnapshot
        .map { it.availableDevices }
        .distinctUntilChanged()

    /**
     * Records the user's explicit choice and opens an attempt (T1/T2/T3). Deliberately does NOT
     * call `audioHandler.selectDevice` — driving belongs to the [pendingRoute] collector.
     */
    fun select(device: AudioDevice) {
        val before = _routeSnapshot.value
        val after = _routeSnapshot.updateAndGet { snap ->
            // T3: a target the library doesn't enumerate can never be confirmed; refuse it instead
            // of parking in an unreachable `Applying`. Empty list = "cannot drive now", not "absent".
            val unreachable = snap.availableDevices.isNotEmpty() &&
                snap.availableDevices.none { it.kind == device.kind }
            if (unreachable) {
                snap
            } else {
                snap.copy(requested = device, state = AudioRouteState.Applying(device, RouteOrigin.USER))
            }
        }
        val applied = after.state as? AudioRouteState.Applying
        if (applied?.device === device) {
            L.i {
                "[call] audioRoute select kind=${device.kind} " +
                    "prevState=${before.state.logName} confirmed=${after.confirmed?.kind} " +
                    "origin=${applied.origin}"
            }
        } else {
            L.w { "[call] audioRoute select rejected kind=${device.kind} reason=notAvailable" }
        }
    }

    /**
     * Rotates to the next available output. Anchors on the device the horn is DEPICTING — the
     * in-flight target the user tapped for ([AudioRouteSnapshot.userPendingRoute]), else the settled
     * belief ([AudioRouteSnapshot.settledRoute]) — so "one tap always moves audio somewhere else" is
     * arithmetic, not luck. Anchoring on `confirmed` alone left the anchor absent on the first tap of
     * a call, `indexOfFirst` returned -1, and the ring restarted at `devices[0]` — a tap that
     * visibly did nothing.
     *
     * `requested` is deliberately NOT a tier: it is durable user intent that outlives the
     * AudioSwitch, not evidence about where audio is — using it anchors the ring on a just-failed
     * target and reproduces the alternating dead tap this formula exists to kill.
     *
     * The `-1` branch stays as a total-function guard: with `userPendingRoute` enumerable by
     * construction and `settledRoute`'s fallback read out of `availableDevices`, it is reachable
     * only via a `confirmed` device the current list no longer holds — where `devices[0]` is a
     * genuine change anyway.
     */
    fun switchToNext() {
        val snap = _routeSnapshot.value
        val devices = snap.availableDevices
        if (devices.size <= 1) {
            L.i { "[call] audioRoute switchToNext skipped count=${devices.size}" }
            return
        }
        val pending = snap.userPendingRoute
        val curKind = (pending ?: snap.settledRoute)?.kind
        val curIdx = devices.indexOfFirst { it.kind == curKind }
        val next = devices[(curIdx + 1) % devices.size]
        L.i {
            "[call] audioRoute switchToNext from=$curKind to=${next.kind} count=${devices.size} " +
                "anchor=${if (pending != null) "pending" else "settled"}"
        }
        select(next)
    }

    /**
     * The only authoritative device-change entry point (rules R0–R6). `librarySelected` is
     * assigned when the library *starts* routing, so it can disprove or trigger verification, but
     * it can never confirm.
     */
    fun onLibraryDevicesChanged(devices: List<AudioDevice>, librarySelected: AudioDevice?) {
        val before = _routeSnapshot.value
        val after = _routeSnapshot.updateAndGet { snap -> reduceLibraryChange(snap, devices, librarySelected) }
        if (after != before) {
            L.i {
                "[call] audioRoute libraryChanged libSelected=${librarySelected?.kind} count=${devices.size} " +
                    "state=${before.state.logName}->${after.state.logName} " +
                    "confirmed=${before.confirmed?.kind}->${after.confirmed?.kind} " +
                    "origin=${(after.state as? AudioRouteState.Applying)?.origin}"
            }
        }
    }

    /** Pure reducer — runs inside a CAS loop, so it must not log or read anything mutable. */
    private fun reduceLibraryChange(
        snap: AudioRouteSnapshot,
        devices: List<AudioDevice>,
        librarySelected: AudioDevice?,
    ): AudioRouteSnapshot {
        // R0: the library's device list is always the truth.
        val withDevices = snap.copy(availableDevices = devices)
        val applyingNow = snap.state as? AudioRouteState.Applying
        // R1a: a LIBRARY attempt may be superseded by a newer, DIFFERENT library pick (e.g. a
        // headset already connected: earpiece enumerates first via R6, Bluetooth lands a poll
        // later). Requiring "non-null AND different kind" excludes the selectDevice(null) round
        // trip, which always reports null first — R1 below exists to survive that echo. A USER
        // attempt never takes this branch. The `requested` check also protects a LIBRARY attempt
        // the guard's ready-edge replay deliberately left un-upgraded because it already targets
        // the user's kind — R1a must not treat that as an ordinary library-to-library handoff.
        if (applyingNow != null && applyingNow.origin == RouteOrigin.LIBRARY &&
            snap.requested?.kind != applyingNow.device.kind &&
            librarySelected != null && librarySelected.kind != applyingNow.device.kind
        ) {
            return withDevices.copy(
                confirmed = null,
                state = AudioRouteState.Applying(librarySelected, RouteOrigin.LIBRARY),
            )
        }
        // R1: an attempt in flight suppresses every remaining disproof rule — the
        // transient-suppression mechanism for the selectDevice(null) round trip (no separate flag),
        // and what lets a LIBRARY attempt survive its own round trip once R1a rules out a re-pick.
        if (snap.state is AudioRouteState.Applying) return withDevices
        val kinds = devices.mapTo(HashSet()) { it.kind }
        val confirmedKind = snap.confirmed?.kind
        // R2: the confirmed device is no longer enumerable, so the check mark would be a lie.
        if (confirmedKind != null && confirmedKind !in kinds) {
            return withDevices.copy(confirmed = null, state = AudioRouteState.Idle)
        }
        // R3: the library routes nothing, so nothing can be active.
        if (librarySelected == null) {
            return withDevices.copy(confirmed = null, state = AudioRouteState.Idle)
        }
        // R4: same route identity — a changed Bluetooth productName must not clear the check mark.
        if (librarySelected.kind == confirmedKind) return withDevices
        // R5: loop guard. Only failures where the device was present the whole time lock out
        // library-driven retries; a target that reappears after DEVICE_GONE is new information.
        // Carrying snap.state by REFERENCE here is contractual — the wedge detector counts
        // attempts by Failed identity (see AudioRouteState.Failed KDoc).
        val failed = snap.state as? AudioRouteState.Failed
        if (failed != null &&
            failed.device.kind == librarySelected.kind &&
            failed.cause != AudioRouteFailure.DEVICE_GONE
        ) {
            return withDevices
        }
        // R6: the library picked a new target on its own — verify it like any other attempt.
        return withDevices.copy(
            confirmed = null,
            state = AudioRouteState.Applying(librarySelected, RouteOrigin.LIBRARY),
        )
    }

    /** Only ever called from an observed routing fact. The single writer of the check mark. */
    override fun onRouteConfirmed(device: AudioDevice) {
        val before = _routeSnapshot.value
        val after = _routeSnapshot.updateAndGet { snap ->
            val applying = snap.state as? AudioRouteState.Applying
            when {
                // No attempt outstanding: this report raced past ownership being lost (a
                // generation invalidation, or a newer attempt already settling). Drop it whole —
                // writing `confirmed` alone would still resurrect a check mark nothing verified.
                applying == null -> snap
                // T4: confirming a different kind is still an observed fact worth recording, but it
                // does not end the outstanding attempt. The counter bump is what makes it visible
                // to the wedge detector — no Confirmed STATE is ever emitted on this branch.
                applying.device.kind != device.kind ->
                    snap.copy(confirmed = device, confirmations = snap.confirmations + 1)
                else -> snap.copy(
                    confirmed = device,
                    state = AudioRouteState.Confirmed(device),
                    confirmations = snap.confirmations + 1,
                )
            }
        }
        if (after == before) {
            L.w { "[call] audioRoute confirmed ignored kind=${device.kind} reason=noOutstandingAttempt" }
        } else {
            L.i { "[call] audioRoute confirmed kind=${device.kind} state=${after.state.logName}" }
        }
    }

    /**
     * Reports that an attempt ended without ever observing the route. `confirmed` is cleared (T6)
     * because the library already tore the route down inside `onActivate(device)`; the library's
     * own fallback pick comes back through [onLibraryDevicesChanged] and is verified normally.
     */
    override fun onRouteFailed(device: AudioDevice, cause: AudioRouteFailure) {
        val after = _routeSnapshot.updateAndGet { snap ->
            val applying = snap.state as? AudioRouteState.Applying
            if (applying == null || applying.device.kind != device.kind) {
                snap
            } else {
                snap.copy(confirmed = null, state = AudioRouteState.Failed(device, cause))
            }
        }
        if ((after.state as? AudioRouteState.Failed)?.device === device) {
            L.w { "[call] audioRoute failed kind=${device.kind} cause=$cause" }
        } else {
            L.w { "[call] audioRoute failed ignored kind=${device.kind} state=${after.state.logName} cause=$cause" }
        }
    }

    /**
     * The `AudioSwitch` instance behind [audioHandler] was destroyed or replaced.
     *
     * Runs above R1 and R5: a generation boundary is not a device callback, and the library sends
     * no callback at all when it tears an `AudioSwitch` down. Without this entry, an attempt from
     * the previous generation survives the boundary, times out, and its `Failed` arms R5 against
     * the new generation's automatic pick — leaving the state stuck with no check mark and no retry.
     *
     * `requested` survives — it is the only source for the ready-edge replay. When [reseed] is true
     * the wipe is followed, in the SAME publish, by a pull of the library's current view, so a
     * spurious boundary self-heals instead of being permanent (the library only pushes on a further
     * change). One publish, not two: an intermediate `Idle`/empty frame could let
     * `distinctUntilChanged` suppress the applier's re-arm after its own ownership check had
     * abandoned the attempt — `Applying` with no driver.
     *
     * [reseed] must be false for a DISCONNECTED boundary: the library publishes the room state
     * BEFORE running the `AudioSwitchHandler.stop()` side effect, so a pull taken there can race
     * ahead of the teardown and return the DYING generation's non-empty view — resurrecting devices
     * nothing can drive and erasing the empty→non-empty edge the guard's ready-edge replay listens
     * for. Only a CONNECTING boundary can have a live switch worth pulling.
     */
    fun onAudioSwitchInvalidated(reason: String, reseed: Boolean = true) {
        // Explicit compareAndSet loop, not updateAndGet: the library must be re-read on every
        // attempt. No hoisted pre-read — a view read once before the loop would be re-applied on
        // every retry and permanently overwrite a device a concurrent push had already delivered
        // (the list stays non-empty, so nothing detects the loss).
        var attempts = 0
        while (true) {
            attempts++
            val before = _routeSnapshot.value
            // Read AFTER sampling `before`: a failing compareAndSet is a monitor-guarded read of
            // the winning writer's published value, so this order is required for that guarantee.
            val view = if (reseed) readLibraryView() else null
            val after = if (view == null) {
                before.wiped()
            } else {
                before.wiped().reseeded(view.devices, view.selected)
            }
            if (_routeSnapshot.compareAndSet(before, after)) {
                // `attempts > 1` evidences a contended recovery even when the re-read recomputes a
                // snapshot identical to a concurrent push's, so the CAS succeeds with no state change.
                if (attempts > 1 || after != before) {
                    L.w {
                        "[call] audioRoute switchInvalidated reason=$reason prevState=${before.state.logName} " +
                            "prevConfirmed=${before.confirmed?.kind} requested=${after.requested?.kind} " +
                            "reseeded=${after.availableDevices.size} state=${after.state.logName} " +
                            "attempts=$attempts"
                    }
                }
                return
            }
        }
    }

    /**
     * Second chance for a snapshot whose device list is still empty: pulls the library's current
     * view through the same door a push uses. Needed because the list is pushed only on a *change*
     * — when a wipe was spurious the library believes nothing changed and will never push again.
     * Used by the lifecycle guard's starvation watchdog; a generation boundary recovers inline in
     * [onAudioSwitchInvalidated] instead.
     *
     * Uses `updateAndGet` with a single pre-loop pull, deliberately: this entry never wipes, so
     * [reseeded]'s empty-list precondition stays live — a lost race just drops the pull in the
     * push's favor, so a stale view is only ever discarded, never applied over fresher data.
     */
    internal fun reseedFromLibrary(reason: String) {
        val view = readLibraryView()
        val before = _routeSnapshot.value
        val after = _routeSnapshot.updateAndGet { snap -> snap.reseeded(view.devices, view.selected) }
        // List identity, not `after != before`: a concurrent push also changes the snapshot, so
        // equality can't tell "our pull landed" from "a push landed instead". The non-empty guard
        // is load-bearing: an empty pull is `kotlin.collections.EmptyList` — the SAME singleton as
        // the snapshot's own empty list — so identity alone would report the genuine-starvation
        // no-op as `applied=true pulled=0`, hiding the exact failure this log exists to expose.
        val applied = view.devices.isNotEmpty() && after.availableDevices === view.devices
        if (applied) {
            L.i {
                "[call] audioRoute reseed reason=$reason pulled=${view.devices.size} applied=true " +
                    "libSelected=${view.selected?.kind} state=${before.state.logName}->${after.state.logName}"
            }
        } else {
            L.w {
                "[call] audioRoute reseed reason=$reason pulled=${view.devices.size} applied=false " +
                    "have=${after.availableDevices.size}"
            }
        }
    }

    /** One coherent read of the library's current view. */
    private class LibraryView(val selected: AudioDevice?, val devices: List<AudioDevice>)

    /**
     * What the library can still tell us right now, as opposed to what it last pushed.
     *
     * Selection is read BEFORE the list: the library writes the device set first and the selection
     * second, then notifies, so reading in that same order means a straddled read can only yield an
     * *older* selection with a *newer* list — never a selection the list never contained.
     *
     * The hazard is a HOISTED pre-read: a view read once before a retry loop would be re-applied on
     * every retry and could permanently overwrite a concurrently pushed list. Any retrying writer
     * must therefore take the read freshly per attempt — [onAudioSwitchInvalidated]'s explicit
     * `compareAndSet` loop calls this once **per attempt**; [reseedFromLibrary] takes a single
     * pre-loop read deliberately, because its reducer can only ever DROP a stale view, never apply
     * it over fresher data.
     *
     * Two separate arguments, neither implying the other: (1) *memory visibility* — the pull reads
     * `_routeSnapshot` on this thread first, so it can never observe a value older than the last
     * push we processed; (2) *lock ordering* — both reads happen with no lock held, so the
     * library's monitor and the flow's monitor are never nested, which is why this call must never
     * move inside `compareAndSet`.
     *
     * Reads only — never `selectDevice`.
     */
    private fun readLibraryView(): LibraryView {
        val selected = audioHandler.selectedAudioDevice
        return LibraryView(selected, audioHandler.availableAudioDevices)
    }

    /**
     * R-pull: a pulled view is a RECOVERY for a snapshot whose list is empty, nothing more. A
     * non-empty list means the library has pushed since the pull was read, so the push wins and the
     * pull is dropped — deciding this inside the CAS makes it atomic with a concurrent push. An
     * empty pull is a no-op, never a wipe: feeding `emptyList()` through the reducer would hit R3
     * and destroy a pending [select] attempt.
     */
    private fun AudioRouteSnapshot.reseeded(devices: List<AudioDevice>, selected: AudioDevice?): AudioRouteSnapshot =
        if (devices.isEmpty() || availableDevices.isNotEmpty()) this
        else reduceLibraryChange(this, devices, selected)

    /** The generation wipe, factored out so it has exactly one definition. `requested` survives. */
    private fun AudioRouteSnapshot.wiped(): AudioRouteSnapshot =
        copy(availableDevices = emptyList(), confirmed = null, state = AudioRouteState.Idle)

    /**
     * Toggles the noise suppression (denoising) feature on/off for the current call.
     */
    fun switchDeNoiseEnable(enabled: Boolean) {
        _deNoiseEnable.value = enabled
    }

    fun switchVoicePreset(preset: VoicePreset) {
        if (_voicePreset.value == preset) return
        _voicePreset.value = preset
    }

    fun switchDeNoiseMode(mode: AudioModule) {
        if (_deNoiseMode.value == mode) return
        _deNoiseMode.value = mode
        val configMode = mode.toConfigMode()
        userManager.update { denoiseMode = configMode }
    }

    fun initDeNoiseMode(mode: AudioModule) {
        _deNoiseMode.value = mode
    }

    companion object {
        private const val CONFIG_MODE_STANDARD = "standard"
        private const val CONFIG_MODE_ENHANCED = "enhanced"

        fun resolveDeNoiseMode(configMode: String?): AudioModule =
            when (configMode) {
                CONFIG_MODE_ENHANCED -> AudioModule.DEEP_FILTER_NET
                else -> AudioModule.RNNOISE
            }

        fun AudioModule.toConfigMode(): String =
            when (this) {
                AudioModule.DEEP_FILTER_NET -> CONFIG_MODE_ENHANCED
                else -> CONFIG_MODE_STANDARD
            }
    }
}
