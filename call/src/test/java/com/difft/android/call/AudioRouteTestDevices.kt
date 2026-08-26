package com.difft.android.call

import android.content.Intent
import android.media.AudioManager
import com.twilio.audioswitch.AudioDevice
import io.mockk.every
import io.mockk.mockk

/**
 * Shared audio-device fixtures for the route tests.
 *
 * Every `AudioDevice` subclass has an `internal constructor`, so neither production nor test code
 * in `:call` can instantiate one — a mock is the only option. MockK instances compare by identity
 * instead of honouring the data class `equals`, which is a second, independent reason production
 * code must compare routes through `kind` and never through `==`: a design based on `==` could not
 * be unit-tested at all.
 */
fun btDevice(label: String = "Bluetooth"): AudioDevice.BluetoothHeadset =
    mockk { every { name } returns label }

fun spkDevice(label: String = "Speakerphone"): AudioDevice.Speakerphone =
    mockk { every { name } returns label }

fun earDevice(label: String = "Earpiece"): AudioDevice.Earpiece =
    mockk { every { name } returns label }

fun wiredDevice(label: String = "Wired Headset"): AudioDevice.WiredHeadset =
    mockk { every { name } returns label }

/** An `ACTION_SCO_AUDIO_STATE_UPDATED` intent carrying [state], for SCO-receiver rows. */
fun scoIntent(state: Int): Intent = mockk {
    every { getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, any()) } returns state
}
