package com.difft.android.base.utils

import com.difft.android.base.utils.globalServices

import android.app.Activity
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SecureModeUtil {

    



    fun refreshByScreenLock(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (isDebugOrInsider()) {
            clearSecureFlag(activity.window)
            return
        }

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return

        scope.launch(Dispatchers.IO) {
            val hasScreenLock = checkHasScreenLock()

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                if (hasScreenLock) {
                    setSecureFlag(activity.window)
                } else {
                    clearSecureFlag(activity.window)
                }
            }
        }
    }

    



    fun refreshWithPagePolicy(activity: Activity, pageEnable: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (isDebugOrInsider()) {
            clearSecureFlag(activity.window)
            return
        }

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return

        scope.launch(Dispatchers.IO) {
            val hasScreenLock = checkHasScreenLock()

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                if (hasScreenLock || !pageEnable) {
                    setSecureFlag(activity.window)
                } else {
                    clearSecureFlag(activity.window)
                }
            }
        }
    }

    
    fun forceDisable(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (isDebugOrInsider()) {
            clearSecureFlag(activity.window)
            return
        }

        setSecureFlag(activity.window)
    }

    



    fun applySecureFlagToDialog(dialog: Dialog) {
        if (isDebugOrInsider()) {
            clearSecureFlag(dialog.window)
            return
        }

        if (checkHasScreenLock()) {
            setSecureFlag(dialog.window)
        }
    }

    

    private fun isDebugOrInsider(): Boolean {
        return BuildConfig.DEBUG || globalServices.environmentHelper.isInsiderChannel()
    }

    private fun checkHasScreenLock(): Boolean {
        val userData = globalServices.userManager.getUserData()
        return userData?.let { !it.passcode.isNullOrEmpty() || !it.pattern.isNullOrEmpty() } ?: false
    }

    private fun setSecureFlag(window: Window?) {
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private fun clearSecureFlag(window: Window?) {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
