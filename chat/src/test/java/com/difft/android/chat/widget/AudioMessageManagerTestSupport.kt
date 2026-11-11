package com.difft.android.chat.widget

import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import org.robolectric.shadows.AudioDeviceInfoBuilder


internal fun deviceOf(type: Int): AudioDeviceInfo =
    AudioDeviceInfoBuilder.newBuilder().setType(type).build()


internal fun setMediaPlayer(value: MediaPlayer?) {
    AudioMessageManager::class.java.getDeclaredField("mediaPlayer")
        .apply { isAccessible = true }
        .set(AudioMessageManager, value)
}
