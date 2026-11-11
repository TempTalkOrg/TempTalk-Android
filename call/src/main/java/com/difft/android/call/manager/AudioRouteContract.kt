package com.difft.android.call.manager

import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow






enum class AudioDeviceKind { EARPIECE, SPEAKERPHONE, WIRED_HEADSET, BLUETOOTH_HEADSET }

val AudioDevice.kind: AudioDeviceKind
    get() = when (this) {
        is AudioDevice.Earpiece -> AudioDeviceKind.EARPIECE
        is AudioDevice.Speakerphone -> AudioDeviceKind.SPEAKERPHONE
        is AudioDevice.WiredHeadset -> AudioDeviceKind.WIRED_HEADSET
        is AudioDevice.BluetoothHeadset -> AudioDeviceKind.BLUETOOTH_HEADSET
    }








enum class RouteOrigin { USER, LIBRARY }


sealed interface AudioRouteState {
    
    data object Idle : AudioRouteState

    




    data class Applying(val device: AudioDevice, val origin: RouteOrigin = RouteOrigin.USER) : AudioRouteState

    
    data class Confirmed(val device: AudioDevice) : AudioRouteState

    










    data class Failed(val device: AudioDevice, val cause: AudioRouteFailure) : AudioRouteState
}







enum class AudioRouteFailure { DEVICE_GONE, TIMEOUT, ERROR }


val AudioRouteState.logName: String
    get() = when (this) {
        AudioRouteState.Idle -> "Idle"
        is AudioRouteState.Applying -> "Applying"
        is AudioRouteState.Confirmed -> "Confirmed"
        is AudioRouteState.Failed -> "Failed"
    }





data class AudioRouteSnapshot(
    
    val availableDevices: List<AudioDevice> = emptyList(),
    
    val requested: AudioDevice? = null,
    
    val confirmed: AudioDevice? = null,
    val state: AudioRouteState = AudioRouteState.Idle,
    







    val confirmations: Int = 0,
)









val AudioRouteSnapshot.settledRoute: AudioDevice?
    get() {
        confirmed?.let { return it }
        val failedKind = (state as? AudioRouteState.Failed)?.device?.kind
        
        return availableDevices.firstOrNull { it.kind != failedKind }
    }











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







val AudioRouteSnapshot.targetedRoute: AudioDevice?
    get() = (state as? AudioRouteState.Applying)?.device ?: confirmed






interface AudioRouteHost {
    



    val pendingRoute: Flow<AudioRouteState.Applying>

    
    val routeSnapshot: StateFlow<AudioRouteSnapshot>

    
    fun onRouteConfirmed(device: AudioDevice)

    




    fun onRouteFailed(device: AudioDevice, cause: AudioRouteFailure)
}
