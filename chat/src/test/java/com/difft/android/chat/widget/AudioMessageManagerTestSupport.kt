package com.difft.android.chat.widget

import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import org.robolectric.shadows.AudioDeviceInfoBuilder

/** Shared test fixtures for [AudioMessageManager] / [ProximitySensorManager] route tests. */
internal fun deviceOf(type: Int): AudioDeviceInfo =
    AudioDeviceInfoBuilder.newBuilder().setType(type).build()

/** [AudioMessageManager] is an `object`, so its `mediaPlayer` field can only be reached by reflection. */
internal fun setMediaPlayer(value: MediaPlayer?) {
    AudioMessageManager::class.java.getDeclaredField("mediaPlayer")
        .apply { isAccessible = true }
        .set(AudioMessageManager, value)
}
