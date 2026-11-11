package com.difft.android.migration.v2.transfer

import android.content.Context
import android.os.Build
import android.provider.Settings







object DeviceNames {

    fun local(context: Context): String {
        val userName = runCatching {
            Settings.Global.getString(context.contentResolver, GLOBAL_DEVICE_NAME)
        }.getOrNull()
        return userName?.trim()?.takeIf { it.isNotEmpty() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    
    private const val GLOBAL_DEVICE_NAME = "device_name"
}
