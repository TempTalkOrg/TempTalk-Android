package com.difft.android.call.ui

import com.difft.android.call.R
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioRouteSnapshot
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.difft.android.call.manager.settledRoute
import com.difft.android.call.manager.userPendingRoute
import com.twilio.audioswitch.AudioDevice








enum class AudioRouteRowStatus { NONE, ACTIVE, CONNECTING, FAILED }


data class AudioDeviceRow(
    val device: AudioDevice,
    val kind: AudioDeviceKind,
    val status: AudioRouteRowStatus,
)











fun AudioRouteSnapshot.toDeviceRows(): List<AudioDeviceRow> {
    val applyingKind = (state as? AudioRouteState.Applying)?.device?.kind
    val failedKind = (state as? AudioRouteState.Failed)?.device?.kind
    val confirmedKind = confirmed?.kind
    return availableDevices.map { device ->
        val kind = device.kind
        AudioDeviceRow(
            device = device,
            kind = kind,
            status = when (kind) {
                applyingKind -> AudioRouteRowStatus.CONNECTING
                failedKind -> AudioRouteRowStatus.FAILED
                confirmedKind -> AudioRouteRowStatus.ACTIVE
                else -> AudioRouteRowStatus.NONE
            },
        )
    }
}


fun AudioRouteRowStatus.labelRes(): Int? = when (this) {
    AudioRouteRowStatus.NONE -> null
    AudioRouteRowStatus.ACTIVE -> R.string.call_audio_device_status_active
    AudioRouteRowStatus.CONNECTING -> R.string.call_audio_device_status_connecting
    AudioRouteRowStatus.FAILED -> R.string.call_audio_device_status_failed
}


fun AudioDeviceKind.pickerIconRes(): Int = when (this) {
    AudioDeviceKind.EARPIECE -> R.drawable.tabler_device_phone
    AudioDeviceKind.SPEAKERPHONE -> R.drawable.tabler_device_speaker
    AudioDeviceKind.WIRED_HEADSET -> R.drawable.tabler_device_headphones
    AudioDeviceKind.BLUETOOTH_HEADSET -> R.drawable.tabler_device_airpods
}


fun AudioDeviceKind.hornIconRes(): Int = when (this) {
    
    
    AudioDeviceKind.EARPIECE -> R.drawable.call_ic_volume_speaker
    AudioDeviceKind.SPEAKERPHONE -> R.drawable.call_ic_volume_speaker
    AudioDeviceKind.WIRED_HEADSET -> R.drawable.call_ic_volume_headphones
    AudioDeviceKind.BLUETOOTH_HEADSET -> R.drawable.call_ic_volume_airpod
}









fun AudioRouteSnapshot.hornIconKind(isOneVOneCall: Boolean): AudioDeviceKind =
    settledRoute?.kind
        ?: if (isOneVOneCall) AudioDeviceKind.EARPIECE else AudioDeviceKind.SPEAKERPHONE







data class HornPresentation(val kind: AudioDeviceKind, val pending: Boolean)













fun AudioRouteSnapshot.hornPresentation(
    isOneVOneCall: Boolean,
    isToggle: Boolean,
): HornPresentation {
    val pending = if (isToggle) userPendingRoute else null
    return HornPresentation(
        kind = pending?.kind ?: hornIconKind(isOneVOneCall),
        pending = pending != null,
    )
}









fun shouldShowAudioDevicePanel(rows: List<AudioDeviceRow>): Boolean =
    rows.any { it.kind == AudioDeviceKind.BLUETOOTH_HEADSET } || rows.size != 2
