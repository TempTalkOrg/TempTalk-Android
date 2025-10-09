package com.difft.android.chat.common

import android.app.Activity
import android.view.WindowManager
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.BuildConfig

object ScreenShotUtil {
    fun setScreenShotEnable(activity: Activity?, enable: Boolean) {
        // Debug或者insider模式下，允许截图
        if (BuildConfig.DEBUG || globalServices.environmentHelper.isInsiderChannel()) {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            return
        }

        //设置屏幕锁的情况下，不允许截图
        val userData = globalServices.userManager.getUserData()
        val hasScreenLock = userData?.let { !it.passcode.isNullOrEmpty() || !it.pattern.isNullOrEmpty() } ?: false
        if (hasScreenLock) {
            activity?.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            return
        }

        if (enable) {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity?.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

}