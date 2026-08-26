package com.difft.android.call.media

import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.manager.AudioRouteHost
import com.difft.android.call.manager.AudioRouteSnapshot
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * Hand-written [AudioRouteHost] for the applier tests.
 *
 * Reproduces the manager's `pendingRoute` semantics verbatim — `distinctUntilChanged` on the whole
 * state, so `Applying(D) -> Idle -> Applying(D)` re-emits — and records the terminal reports instead
 * of interpreting them. A mock cannot express the state-flow derivation, and the real manager would
 * mix its own rules into assertions about the applier.
 *
 * [failSnapshotRead] simulates "an unexpected exception escaped the drive loop": every
 * `AudioManager` touch is already guarded by the applier's own safeRead/safeWrite, so a snapshot
 * read is the only remaining unguarded path and therefore the honest way to reach `Failed(ERROR)`.
 */
internal class FakeAudioRouteHost : AudioRouteHost {

    private val snapshot = MutableStateFlow(AudioRouteSnapshot())

    var failSnapshotRead: Boolean = false

    val confirmed = mutableListOf<AudioDevice>()
    val failed = mutableListOf<Pair<AudioDevice, AudioRouteFailure>>()

    /** Total terminal reports — the "exactly one outcome per attempt" invariant reads this. */
    val terminalCount: Int get() = confirmed.size + failed.size

    override val pendingRoute: Flow<AudioRouteState.Applying> = snapshot
        .map { it.state }
        .distinctUntilChanged()
        .filterIsInstance<AudioRouteState.Applying>()

    override val routeSnapshot: StateFlow<AudioRouteSnapshot>
        get() = if (failSnapshotRead) throw IllegalStateException("snapshot read failed") else snapshot

    override fun onRouteConfirmed(device: AudioDevice) {
        confirmed += device
    }

    override fun onRouteFailed(device: AudioDevice, cause: AudioRouteFailure) {
        failed += device to cause
    }

    fun emit(value: AudioRouteSnapshot) {
        snapshot.value = value
    }

    /** Replaces the reported device list without touching the attempt state. */
    fun setDevices(devices: List<AudioDevice>) {
        snapshot.value = snapshot.value.copy(availableDevices = devices)
    }

    fun setState(state: AudioRouteState) {
        snapshot.value = snapshot.value.copy(state = state)
    }
}

/** An in-flight attempt for [target] with [devices] enumerable. */
internal fun applyingSnapshot(
    target: AudioDevice,
    devices: List<AudioDevice>,
    requested: AudioDevice? = target,
): AudioRouteSnapshot = AudioRouteSnapshot(
    availableDevices = devices,
    requested = requested,
    confirmed = null,
    state = AudioRouteState.Applying(target),
)

/** Every distinct target [manager] has published on [AudioDeviceManager.pendingRoute] so far. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.recordPendingRoutes(manager: AudioDeviceManager): List<AudioDeviceKind> {
    val seen = mutableListOf<AudioDeviceKind>()
    backgroundScope.launch { manager.pendingRoute.collect { seen += it.device.kind } }
    runCurrent()
    return seen
}
