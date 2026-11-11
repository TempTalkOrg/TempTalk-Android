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
                
                
                
                
                L.i {
                    "[call] audioRoute devicesChanged count=${devices.size} " +
                        "kinds=${devices.joinToString(",") { it.kind.name }} " +
                        "libSelected=${selected?.kind}"
                }
                
                
                
                if (selected != null) applyDenoiseFor(selected)
                audioDeviceManager.onLibraryDevicesChanged(devices, selected)
            }
            synchronized(this@CallAudioSetup) {
                if (stopped || registered != null) return@launch
                registered = listener
                audioDeviceManager.audioHandler.registerAudioDeviceChangeListener(listener)
                
                
                
                
                
                
                
                
                routeApplier.start()
            }
        }
    }

    

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
        
        val cachedMode = userManager.getUserData()?.denoiseMode?.takeIf { it.isNotEmpty() }
        val configMode = callConfig.denoise?.mode
        val mode = AudioDeviceManager.resolveDeNoiseMode(cachedMode ?: configMode)
        L.i { "[call] CallAudioSetup initDeNoiseMode cachedMode=$cachedMode, configMode=$configMode, resolved=$mode" }
        audioDeviceManager.initDeNoiseMode(mode)
        audioProcessor.setModule(mode)
    }
}
