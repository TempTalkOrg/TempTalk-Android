package com.difft.android.call.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.manager.AudioRouteHost
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the platform says the current communication route is. */
internal enum class ObservedRoute { BLUETOOTH, SPEAKER, EARPIECE_OR_WIRED }

internal fun ObservedRoute.matches(kind: AudioDeviceKind): Boolean = when (this) {
    ObservedRoute.BLUETOOTH -> kind == AudioDeviceKind.BLUETOOTH_HEADSET
    ObservedRoute.SPEAKER -> kind == AudioDeviceKind.SPEAKERPHONE
    // The library never lists Earpiece and WiredHeadset at the same time
    // (AbstractAudioSwitch.onDeviceConnected drops Earpiece while a WiredHeadset is present,
    // AudioSwitch.onDeviceDisconnected adds it back), and select() only accepts targets that are
    // in the list — so "speaker off + sco off" identifies whichever of the two it is.
    ObservedRoute.EARPIECE_OR_WIRED ->
        kind == AudioDeviceKind.EARPIECE || kind == AudioDeviceKind.WIRED_HEADSET
}

@RequiresApi(Build.VERSION_CODES.S)
private const val TYPE_BLE_HEADSET = AudioDeviceInfo.TYPE_BLE_HEADSET

private val BLUETOOTH_COMM_TYPES: Set<Int> =
    setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET)

private val WIRED_COMM_TYPES: Set<Int> = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

/**
 * The route a communication-device type alone indicates, or null when the type says nothing.
 *
 * Deliberately NOT [inferObservedRoute]'s `else` branch: there, two negative legacy readings mean
 * "earpiece or wired"; here an unrecognised type means "no information", and a wake-up source that
 * guessed would fire on every unrelated endpoint.
 */
internal fun commDeviceRoute(type: Int): ObservedRoute? = when {
    type in BLUETOOTH_COMM_TYPES -> ObservedRoute.BLUETOOTH
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> ObservedRoute.SPEAKER
    type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ObservedRoute.EARPIECE_OR_WIRED
    type in WIRED_COMM_TYPES -> ObservedRoute.EARPIECE_OR_WIRED
    else -> null
}

/**
 * Legacy readings decide; [commType] only cross-checks and adds the LE-Audio case.
 *
 * The audioswitch library routes exclusively through the legacy generation (`isSpeakerphoneOn` /
 * `startBluetoothSco`) and never calls `setCommunicationDevice`, so whether
 * `getCommunicationDevice()` reflects legacy-induced routing is unverified platform behaviour and
 * must not be the primary judgement. It IS trusted as a positive observation: a communication
 * device of TYPE_BLE_HEADSET means the platform's route really is that headset.
 *
 * [commType] is null for API < 31, for a read failure, and for "platform reports none" — all three
 * fall back to the legacy inference, so they are deliberately not distinguished.
 */
internal fun inferObservedRoute(commType: Int?, scoOn: Boolean, speakerOn: Boolean): ObservedRoute {
    val fromComm = commType?.let(::commDeviceRoute)
    return when {
        scoOn || fromComm == ObservedRoute.BLUETOOTH -> ObservedRoute.BLUETOOTH
        speakerOn || fromComm == ObservedRoute.SPEAKER -> ObservedRoute.SPEAKER
        else -> ObservedRoute.EARPIECE_OR_WIRED
    }
}

/**
 * Which signal carried the observation — the field fingerprint that tells "SCO really connected"
 * apart from "only the modern API agrees" and from "inferred from two negatives".
 */
internal fun observedVia(
    route: ObservedRoute,
    scoOn: Boolean,
    speakerOn: Boolean,
    commType: Int?,
): String = when {
    route == ObservedRoute.BLUETOOTH && scoOn -> "sco"
    route == ObservedRoute.SPEAKER && speakerOn -> "speakerphoneOn"
    commType != null -> "commDevice"
    else -> "legacyNegative"
}

/**
 * Turns a route *intent* into a route *fact*: drives the platform, observes what actually happened,
 * retries within a bounded budget, and reports the outcome back to [host].
 *
 * Holds no route state of its own. The only things it owns are the forced-reapply primitive
 * (`selectDevice(null)` -> `selectDevice(device)`), the retry parameters and the audio-mode
 * pre-check; the platform wake-up sources live in [AudioRouteWakeSignals].
 *
 * It deliberately never writes `setCommunicationDevice` / `isSpeakerphoneOn` / `startBluetoothSco`
 * and never calls `host.select()`: every routing action goes through [audioHandler] so the library
 * stays the single writer of the global communication route, and route policy stays in the host.
 */
class AudioRouteApplier(
    appContext: Context,
    private val host: AudioRouteHost,
    private val audioHandler: AudioSwitchHandler,
    private val scope: CoroutineScope,
    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    @VisibleForTesting
    internal val wakeSignals = AudioRouteWakeSignals(appContext, host, audioManager)

    private var collectJob: Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        // Last resort: the collector is dead, so nothing verifies routes for the rest of the call.
        // Loud on purpose — silence here reproduces the original bug's blind spot.
        L.e { "[call] audioRoute applier collector died: ${e.stackTraceToString()}" }
    }

    /** Registers the wake-up sources and starts driving [AudioRouteHost.pendingRoute]. Idempotent. */
    fun start() {
        synchronized(this) {
            if (collectJob != null) {
                L.w { "[call] audioRoute applier start ignored: already started" }
                return
            }
            wakeSignals.start()
            collectJob = scope.launch(workDispatcher + exceptionHandler) {
                // collectLatest, not collect: a new target structurally cancels the previous
                // attempt instead of relying on a flag the loop has to remember to check.
                host.pendingRoute.collectLatest { applying -> runAttempt(applying.device) }
            }
        }
        L.i {
            "[call] audioRoute applier started intervalMs=$RETRY_INTERVAL_MS budgetMs=$BUDGET_MS " +
                wakeSignals.summary()
        }
    }

    /**
     * Cancels any in-flight attempt and unregisters the wake-up sources. Idempotent and callable
     * from any thread; in production it runs on the cleanup executor's IO dispatcher, so the
     * unregister binder calls are already off the main thread.
     */
    fun stop() {
        synchronized(this) {
            collectJob?.cancel()
            collectJob = null
            wakeSignals.stop()
        }
        L.i { "[call] audioRoute applier stopped" }
    }

    private suspend fun runAttempt(target: AudioDevice) {
        L.i { "[call] audioRoute apply begin kind=${target.kind}" }
        // An empty device list means no live AudioSwitch exists, so every selectDevice would be
        // dropped silently. Park OUTSIDE the budget: spending it here would guarantee a fake
        // timeout, and that fake Failed would then arm the retry loop guard against the next
        // generation's own pick.
        awaitDevicesReady(target)
        val outcome = try {
            withTimeoutOrNull(BUDGET_MS) { driveUntilSettled(target) }
                ?: Outcome.Failed(
                    // Classify by whether the target is unreachable *now*, at the end of the
                    // attempt — never by whether it was ever missing during it. A single transient
                    // gap (every disconnect+reconnect pair produces one) inside an
                    // otherwise-present window is a genuine TIMEOUT: the device was there and the
                    // route still failed, which is exactly what the loop guard must latch on.
                    // Latching on history instead would let one twitch disarm the guard and turn
                    // the library's next automatic pick into an unbounded retry loop.
                    if (isTargetAbsent(target)) AudioRouteFailure.DEVICE_GONE
                    else AudioRouteFailure.TIMEOUT
                )
        } catch (e: CancellationException) {
            // Superseded by a newer target (collectLatest) or the call ended. The host already
            // moved on, so reporting failure would be both wrong and dropped. Rethrow, say nothing.
            throw e
        } catch (e: Exception) {
            L.e { "[call] audioRoute apply crashed kind=${target.kind}: ${e.stackTraceToString()}" }
            Outcome.Failed(AudioRouteFailure.ERROR)
        }
        when (outcome) {
            Outcome.Confirmed -> host.onRouteConfirmed(target)
            Outcome.Aborted ->
                L.i { "[call] audioRoute apply aborted kind=${target.kind} reason=ownershipLost" }
            is Outcome.Failed -> host.onRouteFailed(target, outcome.cause)
        }
    }

    private suspend fun awaitDevicesReady(target: AudioDevice) {
        if (host.routeSnapshot.value.availableDevices.isNotEmpty()) return
        L.i { "[call] audioRoute apply parked kind=${target.kind} reason=noDevices" }
        host.routeSnapshot.first { it.availableDevices.isNotEmpty() }
        L.i { "[call] audioRoute apply resumed kind=${target.kind}" }
    }

    private suspend fun driveUntilSettled(target: AudioDevice): Outcome {
        var round = 0
        // Consecutive-only: reset on every present poll, no cross-round latch.
        var absentRounds = 0
        var signalWakes = 0
        while (true) {
            // A generation reset (AudioSwitch torn down) leaves Applying, and pendingRoute never
            // emits for that, so collectLatest cannot cancel us. Abort silently: a generation
            // boundary is not a routing failure and must not surface as one.
            if (!ownsAttempt(target)) return Outcome.Aborted

            // Absence is debounced. The library's scanner mirrors getDevices() verbatim, so ANY
            // disconnect+reconnect pair — notably a headset handing over from another host —
            // produces at least one poll where the target kind is missing. Ending the attempt on
            // that single poll would report DEVICE_GONE for a device that is about to come back.
            if (isTargetAbsent(target)) {
                absentRounds++
                if (absentRounds >= GONE_CONFIRM_ROUNDS) {
                    return Outcome.Failed(AudioRouteFailure.DEVICE_GONE)
                }
                L.i {
                    "[call] audioRoute apply targetAbsent kind=${target.kind} " +
                        "absentRounds=$absentRounds of=$GONE_CONFIRM_ROUNDS"
                }
                // Do not drive: selectDevice on a device the library cannot enumerate is a no-op at
                // best. Just wait out the interval and re-check.
                signalWakes = awaitNextRound(target, signalWakes)
                continue
            }
            absentRounds = 0

            // Observe before driving: a route that is already correct must be confirmed with zero
            // routing actions, otherwise every verification would tear down working audio.
            val reading = observeRoute()
            if (reading.route.matches(target.kind)) {
                L.i {
                    "[call] audioRoute apply confirmed kind=${target.kind} round=$round " +
                        "via=${reading.via}"
                }
                return Outcome.Confirmed
            }
            round++
            val mode = ensureCommunicationMode()
            if (!forceReapply(target)) return Outcome.Aborted
            L.i {
                "[call] audioRoute apply round=$round kind=${target.kind} " +
                    "observed=${reading.route} scoOn=${reading.scoOn} " +
                    "spkOn=${reading.speakerOn} commType=${reading.commType} " +
                    "mode=$mode commDevices=${commDevicesSummary()}"
            }
            signalWakes = awaitNextRound(target, signalWakes)
        }
    }

    private fun ownsAttempt(target: AudioDevice): Boolean {
        val state = host.routeSnapshot.value.state
        return state is AudioRouteState.Applying && state.device.kind == target.kind
    }

    /**
     * One poll's answer to "is the target enumerable right now".
     *
     * An EMPTY list is "cannot drive right now", never "the device was removed": the library emits
     * no callback when its switch is torn down, so emptiness comes from our own generation reset.
     * A non-empty list without the target kind is a *candidate* removal — the caller debounces it
     * over [GONE_CONFIRM_ROUNDS] polls before declaring DEVICE_GONE.
     */
    private fun isTargetAbsent(target: AudioDevice): Boolean {
        val devices = host.routeSnapshot.value.availableDevices
        return devices.isNotEmpty() && devices.none { it.kind == target.kind }
    }

    /**
     * The forced-reapply primitive. Must stay non-`suspend`: with no suspension point between the
     * ownership check and the two calls, a superseded attempt structurally cannot drive the platform.
     * Never hop to the main looper — `selectDevice` is `@Synchronized` and posts to the library's own
     * handler thread, so an enqueued hop only adds a window for a stale post to land.
     *
     * `selectDevice(null)` first is what breaks the library's "already selected, nothing to do"
     * early return.
     *
     * @return false when the attempt was superseded; the caller must abort instead of driving or
     *   logging a round it did not perform.
     */
    private fun forceReapply(target: AudioDevice): Boolean {
        if (!ownsAttempt(target)) {
            val now = (host.routeSnapshot.value.state as? AudioRouteState.Applying)?.device?.kind
            L.w { "[call] audioRoute reapply skipped kind=${target.kind} reason=staleAttempt now=$now" }
            return false
        }
        try {
            audioHandler.selectDevice(null)
            audioHandler.selectDevice(target)
        } catch (e: Exception) {
            L.w { "[call] audioRoute reapply failed kind=${target.kind}: ${e.message}" }
        }
        return true
    }

    /**
     * Waits out one retry interval, or less if a wake-up source signals.
     *
     * @return the updated wake count. Past [MAX_SIGNAL_WAKES] signals are ignored for the rest of
     *   the attempt: a signal may replace an interval, never add rounds.
     */
    private suspend fun awaitNextRound(target: AudioDevice, wakes: Int): Int {
        if (wakes >= MAX_SIGNAL_WAKES) {
            delay(RETRY_INTERVAL_MS)
            return wakes
        }
        val woke = withTimeoutOrNull(RETRY_INTERVAL_MS) { wakeSignals.await() } != null
        if (!woke) return wakes
        val next = wakes + 1
        if (next == MAX_SIGNAL_WAKES) {
            L.w { "[call] audioRoute applySignalCap kind=${target.kind} wakes=$next" }
        }
        return next
    }

    /**
     * `isBluetoothScoOn` / `isSpeakerphoneOn` are deprecated in favour of `getCommunicationDevice`,
     * but the audioswitch library routes exclusively through the legacy generation, so these two
     * are what actually reflect the route it produced. The modern reading is kept as a cross-check
     * only — see [inferObservedRoute].
     */
    @Suppress("DEPRECATION")
    private fun observeRoute(): RouteReading {
        val scoOn = safeRead("isBluetoothScoOn") { audioManager.isBluetoothScoOn } ?: false
        val speakerOn = safeRead("isSpeakerphoneOn") { audioManager.isSpeakerphoneOn } ?: false
        val commType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeRead("communicationDevice") { audioManager.communicationDevice?.type }
        } else {
            null
        }
        return RouteReading(inferObservedRoute(commType, scoOn, speakerOn), scoOn, speakerOn, commType)
    }

    /**
     * Corrects the system audio mode when it drifted away from a routable one.
     *
     * `AbstractAudioSwitch.shouldHandleAudioRouting()` reads the library's OWN expected mode field,
     * not the system's, so the library keeps believing it can route while the platform does not
     * honour it. MODE_IN_CALL is accepted, not overwritten — a real cellular call owns that mode
     * and clobbering it would break it.
     *
     * @return the mode as OBSERVED, before any correction — the round log must not report the fix.
     */
    private fun ensureCommunicationMode(): Int? {
        val mode = safeRead("mode") { audioManager.mode } ?: return null
        if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) return mode
        L.w { "[call] audioRoute mode drifted actual=$mode correcting=MODE_IN_COMMUNICATION" }
        safeWrite("mode") { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        return mode
    }

    /**
     * What `setCommunicationDevice` could target this round, as `type:id` pairs — never device
     * names (a Bluetooth name is the owner's product name).
     */
    private fun commDevicesSummary(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeRead("availableCommunicationDevices") {
                audioManager.availableCommunicationDevices.joinToString(",") { "${it.type}:${it.id}" }
            } ?: "?"
        } else {
            "n/a"
        }

    private inline fun <T> safeRead(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            L.w { "[call] audioRoute read failed what=$what: ${e.message}" }
            null
        }

    private inline fun safeWrite(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            L.w { "[call] audioRoute write failed what=$what: ${e.message}" }
        }
    }

    private sealed interface Outcome {
        data object Confirmed : Outcome

        /** Ownership of the attempt was lost; nothing is reported to the host. */
        data object Aborted : Outcome
        data class Failed(val cause: AudioRouteFailure) : Outcome
    }

    private data class RouteReading(
        val route: ObservedRoute,
        val scoOn: Boolean,
        val speakerOn: Boolean,
        val commType: Int?,
    ) {
        val via: String get() = observedVia(route, scoOn, speakerOn, commType)
    }

    private companion object {
        /**
         * Verbatim from the library's own legacy SCO job (BluetoothScoJob.kt:10,54 — TIMEOUT =
         * 5000L, postDelayed 500): semantics that were always meant to apply here but are dead code
         * on minSdk 26, where AudioSwitchHandler always constructs the non-legacy switch.
         * Deliberately NOT new numbers.
         */
        const val RETRY_INTERVAL_MS = 500L
        const val BUDGET_MS = 5000L

        /**
         * Consecutive polls with the target missing before it counts as removed (~1.0-1.5s at
         * [RETRY_INTERVAL_MS]). NOT copied from the library: it has no notion of absence at all.
         * Sized to swallow the single-poll gap that any Bluetooth disconnect+reconnect pair
         * produces, while still reporting a genuine unplug well inside the budget.
         */
        const val GONE_CONFIRM_ROUNDS = 3

        /**
         * Signal-driven early wakes allowed per attempt. Bounds an attempt at twice the poll-only
         * round count: a signal may make a round happen earlier, never add one. Derived, NOT a new
         * timing parameter — retuning the budget or interval keeps the invariant by construction.
         */
        val MAX_SIGNAL_WAKES = (BUDGET_MS / RETRY_INTERVAL_MS).toInt()
    }
}
