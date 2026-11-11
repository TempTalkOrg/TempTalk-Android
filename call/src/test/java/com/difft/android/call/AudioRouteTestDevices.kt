package com.difft.android.call

import android.content.Intent
import android.media.AudioManager
import com.twilio.audioswitch.AudioDevice
import io.mockk.every
import io.mockk.mockk










fun btDevice(label: String = "Bluetooth"): AudioDevice.BluetoothHeadset =
    mockk { every { name } returns label }

fun spkDevice(label: String = "Speakerphone"): AudioDevice.Speakerphone =
    mockk { every { name } returns label }

fun earDevice(label: String = "Earpiece"): AudioDevice.Earpiece =
    mockk { every { name } returns label }

fun wiredDevice(label: String = "Wired Headset"): AudioDevice.WiredHeadset =
    mockk { every { name } returns label }


fun scoIntent(state: Int): Intent = mockk {
    every { getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, any()) } returns state
}
