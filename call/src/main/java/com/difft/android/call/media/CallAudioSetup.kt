package com.difft.android.call.media

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.UserManager
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.kind
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the audio-device / denoise bring-up that used to live in
 * `LCallViewModel.initAudioDeviceChangeListener` + `initDeNoiseMode`.
 *
 * Keeps the platform-specific Bluetooth exclusion regex in a single place and
 * owns the full lifecycle (register on `start()`, unregister on `stop()`) of
 * the `AudioDeviceChangeListener` — so callers never touch the listener
 * reference directly.
 *
 * Thread-safety:
 *  - `registered` / `stopped` are `@Volatile` for cross-thread visibility
 *    (writer runs on `Dispatchers.IO`, `stop()` may be called from any thread
 *    including the cleanup executor's IO dispatcher).
 *  - The bring-up block is guarded by `synchronized(this)` so `stop()` cannot
 *    race between the "not stopped" check and the bring-up side effects. The
 *    listener registration and `routeApplier.start()` share one critical
 *    section, so a `stop()` firing before the `start()` coroutine finishes
 *    (e.g. user hangs up immediately after VM init) can never leave either of
 *    them live.
 */
class CallAudioSetup(
    private val scope: CoroutineScope,
    private val audioDeviceManager: AudioDeviceManager,
    private val audioProcessor: AudioPipelineProcessor,
    private val callConfig: CallConfig,
    private val isDenoiseEnabledProvider: () -> Boolean,
    private val userManager: UserManager,
    private val routeApplier: AudioRouteApplier,
) {
    @Volatile private var registered: AudioDeviceChangeListener? = null
    @Volatile private var stopped = false

    /** Created in Phase B: the room, and therefore its state flow, does not exist at construction. */
    private var routeGuard: AudioRouteLifecycleGuard? = null

    fun start() {
        if (stopped) {
            L.w { "[call] CallAudioSetup.start() ignored: already stopped" }
            return
        }
        if (registered != null) {
            L.w { "[call] CallAudioSetup.start() ignored: listener already registered" }
            return
        }
        initDeNoiseMode()
        scope.launch(Dispatchers.IO) {
            val listener: AudioDeviceChangeListener = { devices, selected ->
                // Milestone level, not debug: FileLoggingTree drops D, which made the only
                // authoritative device-change callback invisible in field logs.
                // Log `kind`, never `name` — for Bluetooth `name` is the headset productName and
                // usually carries the owner's real name.
                L.i {
                    "[call] audioRoute devicesChanged count=${devices.size} " +
                        "kinds=${devices.joinToString(",") { it.kind.name }} " +
                        "libSelected=${selected?.kind}"
                }
                // A null selection carries no device name, so it cannot match the exclusion regex;
                // recomputing on it would flap denoise during the selectDevice(null) round trip
                // that every retry round performs.
                if (selected != null) applyDenoiseFor(selected)
                audioDeviceManager.onLibraryDevicesChanged(devices, selected)
            }
            synchronized(this@CallAudioSetup) {
                if (stopped || registered != null) return@launch
                registered = listener
                audioDeviceManager.audioHandler.registerAudioDeviceChangeListener(listener)
                // After registration so the library's device list reaches the manager before the
                // applier can drive anything, and inside the same critical section: outside it a
                // stop() could land between the two, leaving the applier started with its SCO
                // receiver on the application context for the rest of the process plus an orphan
                // collect job, which stop()'s `stopped` guard makes unreachable for a second
                // cleanup. AudioRouteApplier never calls back into this class, so the nested lock
                // cannot deadlock; its registerReceiver is a short binder call and stop() already
                // runs off the main thread.
                routeApplier.start()
            }
        }
    }

    /**
     * Phase B hook: `Room.state` is what drives the library's AudioSwitch create/destroy, so the
     * route-lifecycle guard can only be wired once the room exists. Reuses this class's `stopped`
     * latch so a hang-up racing Phase B cannot leak the guard's collectors.
     *
     * Takes the state flow rather than the `Room` so the guard stays independent of the room
     * object's fail-loud post-release getter.
     */
    fun bindRoomState(roomState: StateFlow<Room.State>) {
        synchronized(this) {
            if (stopped) {
                L.w { "[call] CallAudioSetup.bindRoomState ignored: already stopped" }
                return
            }
            if (routeGuard != null) {
                L.w { "[call] CallAudioSetup.bindRoomState ignored: already bound" }
                return
            }
            routeGuard = AudioRouteLifecycleGuard(scope, audioDeviceManager, roomState)
                .also { it.start() }
        }
    }

    /**
     * Unregister the listener if present and mark this instance as stopped so
     * any still-pending `start()` coroutine becomes a no-op. Idempotent and
     * safe to call from any thread.
     */
    fun stop() {
        synchronized(this) {
            if (stopped) return
            stopped = true
            routeGuard?.stop()
            routeGuard = null
            registered?.let {
                runCatching { audioDeviceManager.audioHandler.unregisterAudioDeviceChangeListener(it) }
                    .onFailure { e -> L.w { "[call] CallAudioSetup.stop unregister failed: ${e.message}" } }
            }
            registered = null
        }
        // Outside the lock: cancels the in-flight attempt and unregisters the SCO receiver.
        // Idempotent, and already off the main thread via the cleanup executor's IO dispatcher.
        routeApplier.stop()
    }

    private fun applyDenoiseFor(selected: AudioDevice?) {
        val excludedNameRegex = callConfig.denoise?.bluetooth?.excludedNameRegex
        val deviceName = selected?.name
        val excluded = !excludedNameRegex.isNullOrEmpty() && !deviceName.isNullOrEmpty() &&
            Regex(excludedNameRegex, RegexOption.IGNORE_CASE).containsMatchIn(deviceName)
        if (excluded) {
            L.i { "[call] CallAudioSetup device in excludedNameRegex = $excludedNameRegex" }
            audioProcessor.setDenoiseEnabled(false)
        } else {
            audioProcessor.setDenoiseEnabled(isDenoiseEnabledProvider())
        }
    }

    private fun initDeNoiseMode() {
        // Synchronous read via UserManager's in-memory snapshot — no I/O.
        val cachedMode = userManager.getUserData()?.denoiseMode?.takeIf { it.isNotEmpty() }
        val configMode = callConfig.denoise?.mode
        val mode = AudioDeviceManager.resolveDeNoiseMode(cachedMode ?: configMode)
        L.i { "[call] CallAudioSetup initDeNoiseMode cachedMode=$cachedMode, configMode=$configMode, resolved=$mode" }
        audioDeviceManager.initDeNoiseMode(mode)
        audioProcessor.setModule(mode)
    }
}
