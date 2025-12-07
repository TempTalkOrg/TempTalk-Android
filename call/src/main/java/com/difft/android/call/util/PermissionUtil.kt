package com.difft.android.call.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtil {

    fun isRuntimePermissionsRequired(): Boolean {
        return Build.VERSION.SDK_INT >= 23
    }

    fun hasAll(context: Context, vararg permissions: String): Boolean {
        if (!isRuntimePermissionsRequired()) {
            return true
        }
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}