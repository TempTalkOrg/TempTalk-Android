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

    




    private val _routeSnapshot = MutableStateFlow(AudioRouteSnapshot())
    override val routeSnapshot: StateFlow<AudioRouteSnapshot> = _routeSnapshot.asStateFlow()

    val requested: AudioDevice? get() = routeSnapshot.value.requested
    val confirmed: AudioDevice? get() = routeSnapshot.value.confirmed
    val routeState: AudioRouteState get() = routeSnapshot.value.state

    




    override val pendingRoute: Flow<AudioRouteState.Applying> = routeSnapshot
        .map { it.state }
        .distinctUntilChanged()
        .filterIsInstance<AudioRouteState.Applying>()

    
    val availableDevices: Flow<List<AudioDevice>> = routeSnapshot
        .map { it.availableDevices }
        .distinctUntilChanged()

    



    fun select(device: AudioDevice) {
        val before = _routeSnapshot.value
        val after = _routeSnapshot.updateAndGet { snap ->
            
            
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

    
    private fun reduceLibraryChange(
        snap: AudioRouteSnapshot,
        devices: List<AudioDevice>,
        librarySelected: AudioDevice?,
    ): AudioRouteSnapshot {
        
        val withDevices = snap.copy(availableDevices = devices)
        val applyingNow = snap.state as? AudioRouteState.Applying
        
        
        
        
        
        
        
        if (applyingNow != null && applyingNow.origin == RouteOrigin.LIBRARY &&
            snap.requested?.kind != applyingNow.device.kind &&
            librarySelected != null && librarySelected.kind != applyingNow.device.kind
        ) {
            return withDevices.copy(
                confirmed = null,
                state = AudioRouteState.Applying(librarySelected, RouteOrigin.LIBRARY),
            )
        }
        
        
        
        if (snap.state is AudioRouteState.Applying) return withDevices
        val kinds = devices.mapTo(HashSet()) { it.kind }
        val confirmedKind = snap.confirmed?.kind
        
        if (confirmedKind != null && confirmedKind !in kinds) {
            return withDevices.copy(confirmed = null, state = AudioRouteState.Idle)
        }
        
        if (librarySelected == null) {
            return withDevices.copy(confirmed = null, state = AudioRouteState.Idle)
        }
        
        if (librarySelected.kind == confirmedKind) return withDevices
        
        
        
        
        val failed = snap.state as? AudioRouteState.Failed
        if (failed != null &&
            failed.device.kind == librarySelected.kind &&
            failed.cause != AudioRouteFailure.DEVICE_GONE
        ) {
            return withDevices
        }
        
        return withDevices.copy(
            confirmed = null,
            state = AudioRouteState.Applying(librarySelected, RouteOrigin.LIBRARY),
        )
    }

    
    override fun onRouteConfirmed(device: AudioDevice) {
        val before = _routeSnapshot.value
        val after = _routeSnapshot.updateAndGet { snap ->
            val applying = snap.state as? AudioRouteState.Applying
            when {
                
                
                
                applying == null -> snap
                
                
                
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

    




















    fun onAudioSwitchInvalidated(reason: String, reseed: Boolean = true) {
        
        
        
        
        var attempts = 0
        while (true) {
            attempts++
            val before = _routeSnapshot.value
            
            
            val view = if (reseed) readLibraryView() else null
            val after = if (view == null) {
                before.wiped()
            } else {
                before.wiped().reseeded(view.devices, view.selected)
            }
            if (_routeSnapshot.compareAndSet(before, after)) {
                
                
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

    










    internal fun reseedFromLibrary(reason: String) {
        val view = readLibraryView()
        val before = _routeSnapshot.value
        val after = _routeSnapshot.updateAndGet { snap -> snap.reseeded(view.devices, view.selected) }
        
        
        
        
        
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

    
    private class LibraryView(val selected: AudioDevice?, val devices: List<AudioDevice>)

    





















    private fun readLibraryView(): LibraryView {
        val selected = audioHandler.selectedAudioDevice
        return LibraryView(selected, audioHandler.availableAudioDevices)
    }

    






    private fun AudioRouteSnapshot.reseeded(devices: List<AudioDevice>, selected: AudioDevice?): AudioRouteSnapshot =
        if (devices.isEmpty() || availableDevices.isNotEmpty()) this
        else reduceLibraryChange(this, devices, selected)

    
    private fun AudioRouteSnapshot.wiped(): AudioRouteSnapshot =
        copy(availableDevices = emptyList(), confirmed = null, state = AudioRouteState.Idle)

    


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
