package com.difft.android.selector.utils

import android.os.Build

import androidx.annotation.ChecksSdkIntAtLeast

object SdkVersionUtils {

    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
    fun isP(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    fun isQ(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isR(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun isTIRAMISU(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun isUPSIDE_DOWN_CAKE(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
