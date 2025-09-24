package com.difft.android.call.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

object AudioDeviceUtil {

    @RequiresApi(Build.VERSION_CODES.S)
    private const val TYPE_BLE_HEADSET = AudioDeviceInfo.TYPE_BLE_HEADSET
    private const val TYPE_BLUETOOTH_SCO = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private const val TYPE_WIRED_HEADSET = AudioDeviceInfo.TYPE_WIRED_HEADSET
    private const val TYPE_WIRED_HEADPHONES = AudioDeviceInfo.TYPE_WIRED_HEADPHONES

    /**
     * Checks if a Bluetooth (BLE/SCO) or wired headset is currently connected.
     * @return true if a supported headset is connected, false otherwise
     */
    fun isBluetoothOrWiredHeadsetConnected(context: Context): Boolean {

        val bleOrWiredDevices = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                listOf(TYPE_BLE_HEADSET, TYPE_BLUETOOTH_SCO, TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES)
            else -> listOf(TYPE_BLUETOOTH_SCO, TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val inputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray<AudioDeviceInfo>()

        return inputDevices.any { it.type in bleOrWiredDevices }
    }
}