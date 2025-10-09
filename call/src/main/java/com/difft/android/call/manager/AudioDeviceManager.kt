package com.difft.android.call.manager

import android.content.Context
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.BuildConfig
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioDeviceManager(
    private val context: Context,
    private val callType: String
) {
    private val _deNoiseEnable = MutableStateFlow(true)
    val deNoiseEnable = _deNoiseEnable.asStateFlow()

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

    private val _selected = MutableStateFlow(audioHandler.selectedAudioDevice)
    val selected = _selected.asStateFlow()

    /**
     * Updates the currently selected audio device to the specified device.
     */
    fun update(selectedAudioDevice: AudioDevice?) {
        _selected.value = selectedAudioDevice
    }

    /**
     * Selects the specified audio device for output.
     */
    fun select(device: AudioDevice) {
        try {
            audioHandler.selectDevice(device)
            _selected.value = device
        } catch (e: Exception) {
            L.e { "[call] AudioDeviceManager select error = ${e.message}" }
        }
    }

    /**
     * Switches the audio output device to the next available device that's different from the current one.
     */
    fun switchToNext() {
        val cur = _selected.value
        val next = audioHandler.availableAudioDevices.firstOrNull { it != cur }
        if (next != null) select(next)
    }

    /**
     * Toggles the noise suppression (denoising) feature on/off for the current call.
     */
    fun switchDeNoiseEnable(enabled: Boolean) {
        _deNoiseEnable.value = enabled
    }
}
