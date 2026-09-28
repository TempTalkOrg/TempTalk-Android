package com.difft.android.migration.v2.transfer

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The human-readable name this device presents to its migration peer — in the receiver's QR
 * (`alias`) and in the sender's `prepare-upload` `info.alias` — so each side can confirm who it is
 * talking to before and during the transfer. The user's own device name (Settings › About › Device
 * name) when set, else manufacturer + model.
 */
object DeviceNames {

    fun local(context: Context): String {
        val userName = runCatching {
            Settings.Global.getString(context.contentResolver, GLOBAL_DEVICE_NAME)
        }.getOrNull()
        return userName?.trim()?.takeIf { it.isNotEmpty() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    /** The key behind `Settings.Global.DEVICE_NAME` (API 25+); spelled out so older targets compile too. */
    private const val GLOBAL_DEVICE_NAME = "device_name"
}
