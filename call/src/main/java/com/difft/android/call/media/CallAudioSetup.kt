package com.difft.android.call.media

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.UserManager
import com.difft.android.call.manager.AudioDeviceManager
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 *  - The register block is guarded by `synchronized(this)` so `stop()` cannot
 *    race between the "not stopped" check and the actual registration —
 *    preventing a leaked listener if `stop()` fires before the `start()`
 *    coroutine finishes (e.g. user hangs up immediately after VM init).
 */
class CallAudioSetup(
    private val scope: CoroutineScope,
    private val audioDeviceManager: AudioDeviceManager,
    private val audioProcessor: AudioPipelineProcessor,
    private val callConfig: CallConfig,
    private val isDenoiseEnabledProvider: () -> Boolean,
    private val userManager: UserManager,
) {
    @Volatile private var registered: AudioDeviceChangeListener? = null
    @Volatile private var stopped = false

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
            val listener: AudioDeviceChangeListener = { _, selected ->
                L.d { "[call] CallAudioSetup selectedAudioDevice = ${selected?.name}" }
                applyDenoiseFor(selected)
                audioDeviceManager.update(selected)
            }
            synchronized(this@CallAudioSetup) {
                if (stopped || registered != null) return@launch
                registered = listener
                audioDeviceManager.audioHandler.registerAudioDeviceChangeListener(listener)
            }
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
            registered?.let {
                runCatching { audioDeviceManager.audioHandler.unregisterAudioDeviceChangeListener(it) }
                    .onFailure { e -> L.w { "[call] CallAudioSetup.stop unregister failed: ${e.message}" } }
            }
            registered = null
        }
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
