package com.difft.android.call.manager

import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Route identity at the granularity audioswitch itself uses. `AudioDevice.equals` compares `name`,
 * and for Bluetooth `name` is the headset productName — it can differ between list refreshes. Every
 * route comparison in :call must go through [kind], never `==`.
 */
enum class AudioDeviceKind { EARPIECE, SPEAKERPHONE, WIRED_HEADSET, BLUETOOTH_HEADSET }

val AudioDevice.kind: AudioDeviceKind
    get() = when (this) {
        is AudioDevice.Earpiece -> AudioDeviceKind.EARPIECE
        is AudioDevice.Speakerphone -> AudioDeviceKind.SPEAKERPHONE
        is AudioDevice.WiredHeadset -> AudioDeviceKind.WIRED_HEADSET
        is AudioDevice.BluetoothHeadset -> AudioDeviceKind.BLUETOOTH_HEADSET
    }

/**
 * Who chose the device now being applied. [USER] comes only from [AudioDeviceManager.select] (a
 * tap, or [AudioDeviceManager.switchToNext]); [LIBRARY] only from rule R6, the library auto-picking
 * with no user action. The split exists for R1a in `reduceLibraryChange`: a USER attempt survives
 * every library callback unconditionally (R1); a LIBRARY attempt may be superseded by a newer,
 * different library pick (a library-to-library handoff, not a user choice being second-guessed).
 */
enum class RouteOrigin { USER, LIBRARY }

/** Lifecycle of the current routing attempt. Never derived from "request delivered". */
sealed interface AudioRouteState {
    /** No routing attempt outstanding and nothing observed as active. */
    data object Idle : AudioRouteState

    /**
     * A route to [device] is being driven and has NOT been observed as active yet. [origin] is
     * load-bearing — see rule R1a in [AudioDeviceManager.reduceLibraryChange]. Defaults to
     * [RouteOrigin.USER] because every call site but R6 constructs a user-driven attempt.
     */
    data class Applying(val device: AudioDevice, val origin: RouteOrigin = RouteOrigin.USER) : AudioRouteState

    /** [device] was observed as the active route. The only success state. */
    data class Confirmed(val device: AudioDevice) : AudioRouteState

    /**
     * Routing to [device] was driven but never observed as active. [cause] is load-bearing: the
     * retry loop guard (rule R5 in [AudioDeviceManager]) treats "not even enumerable" differently
     * from "present the whole time and still never confirmed".
     *
     * Instance identity is part of the contract: [AudioDeviceManager]'s `onRouteFailed` allocates
     * a NEW instance per ended attempt, and rule R5 carries the SAME instance through device-list
     * changes. The lifecycle guard's wedge detector counts attempts by reference, so a reducer
     * change that reconstructs a `Failed` (`copy()`, normalization) silently changes how many
     * attempts it counts.
     */
    data class Failed(val device: AudioDevice, val cause: AudioRouteFailure) : AudioRouteState
}

/**
 * Why an attempt ended without confirmation. [DEVICE_GONE] includes a cross-host Bluetooth
 * handover (OS drops and re-adds the headset) — that is NEW information on return, so it must NOT
 * arm the R5 loop guard. [TIMEOUT] and [ERROR] mean the device was present throughout; repeating
 * those on every device-list twitch is what R5 exists to stop.
 */
enum class AudioRouteFailure { DEVICE_GONE, TIMEOUT, ERROR }

/** Log-only discriminator; deliberately carries no device name. */
val AudioRouteState.logName: String
    get() = when (this) {
        AudioRouteState.Idle -> "Idle"
        is AudioRouteState.Applying -> "Applying"
        is AudioRouteState.Confirmed -> "Confirmed"
        is AudioRouteState.Failed -> "Failed"
    }

/**
 * Atomic snapshot of the audio-route truth. All fields move together — no consumer observes a
 * torn intermediate frame.
 */
data class AudioRouteSnapshot(
    /** Devices the library last reported. Empty until the first library callback. */
    val availableDevices: List<AudioDevice> = emptyList(),
    /** The user's explicit choice. Survives AudioSwitch rebuilds. `null` until the user picks. */
    val requested: AudioDevice? = null,
    /** The last OBSERVED active route — the only source for the `✓`. */
    val confirmed: AudioDevice? = null,
    val state: AudioRouteState = AudioRouteState.Idle,
    /**
     * Monotonic count of accepted confirmations. Proof-of-life for liveness detectors: [confirmed]
     * can be recorded without a state change (T4, cross-kind confirm) and is cleared again by a
     * later failure (T6), so a conflating `StateFlow` can collapse a confirm into the next
     * failure's frame and destroy both other traces of it. Consumers that must not miss a
     * confirmation — the lifecycle guard's wedge detector — watch this counter, which only ever
     * advances. Survives generation wipes.
     */
    val confirmations: Int = 0,
)

/**
 * The route defensible as current from evidence alone: an observed fact, else the device the
 * library itself would route to. The just-failed target is excluded (`onRouteFailed` runs after the
 * library already tore that route down). [AudioRouteState.Applying] is deliberately absent —
 * "where audio is going" is [userPendingRoute]'s question, and conflating the two is the `✓`
 * assertion PR #1120 removed. Inverse precedence of [targetedRoute]; not interchangeable. `null`
 * only when nothing is observed and nothing is enumerated.
 */
val AudioRouteSnapshot.settledRoute: AudioDevice?
    get() {
        confirmed?.let { return it }
        val failedKind = (state as? AudioRouteState.Failed)?.device?.kind
        // availableDevices is priority-sorted, so index 0 is the library's own pick.
        return availableDevices.firstOrNull { it.kind != failedKind }
    }

/**
 * The in-flight attempt the USER asked for that the toggle surface can still honestly depict. Three
 * load-bearing conjuncts: `origin == USER` (a LIBRARY attempt has no outstanding gesture to
 * acknowledge); enumerable in [AudioRouteSnapshot.availableDevices] (else depicting it is a promise
 * this contract cannot keep, and as a ring anchor it lands `indexOfFirst` on -1, restarting the ring
 * at `devices[0]` — the dead-toggle-tap bug); and not Bluetooth (PR #1120: a Bluetooth activation
 * never earns a horn-level optimistic visual — a code property here, not left as a side effect of
 * the panel formula). An acknowledgement can be withdrawn mid-flight if its target stops being
 * enumerable — truthful, since the attempt can no longer succeed as depicted.
 */
val AudioRouteSnapshot.userPendingRoute: AudioDevice?
    get() {
        val pending = (state as? AudioRouteState.Applying)
            ?.takeIf { it.origin == RouteOrigin.USER }
            ?.device
            ?: return null
        val toggleEligible = pending.kind != AudioDeviceKind.BLUETOOTH_HEADSET &&
            availableDevices.any { it.kind == pending.kind }
        return pending.takeIf { toggleEligible }
    }

/**
 * What the route machinery is currently AIMED at — in-flight target first, observed fact second.
 * Inverse precedence of [settledRoute]; not interchangeable. Used by the lifecycle guard's
 * ready-edge replay dedup: an in-flight attempt must count as already-targeted, or the replay calls
 * [AudioDeviceManager.select] again and refreshes the applier's retry budget on every ready edge.
 */
val AudioRouteSnapshot.targetedRoute: AudioDevice?
    get() = (state as? AudioRouteState.Applying)?.device ?: confirmed

/**
 * What the route applier is allowed to see and say. Implemented by [AudioDeviceManager]; lets the
 * applier be unit-tested against a fake without an `AudioSwitchHandler`. Deliberately excludes
 * `onAudioSwitchInvalidated` — resetting the AudioSwitch generation is the lifecycle guard's job.
 */
interface AudioRouteHost {
    /**
     * Emits each *distinct* route target to drive and verify. Re-requesting the target already
     * being applied does NOT re-emit (an attempt is already in flight).
     */
    val pendingRoute: Flow<AudioRouteState.Applying>

    /** Read-only snapshot access for verification, ownership checks and logging. */
    val routeSnapshot: StateFlow<AudioRouteSnapshot>

    /** Call ONLY from an observed routing fact (SCO connected / communication device matched). */
    fun onRouteConfirmed(device: AudioDevice)

    /**
     * The attempt's verification budget expired, or the target became unreachable. "Gone" requires
     * the device list to be NON-EMPTY and lack the target kind for several consecutive polls (see
     * the applier's absence debounce) — an EMPTY list means "cannot drive right now", not "removed".
     */
    fun onRouteFailed(device: AudioDevice, cause: AudioRouteFailure)
}
