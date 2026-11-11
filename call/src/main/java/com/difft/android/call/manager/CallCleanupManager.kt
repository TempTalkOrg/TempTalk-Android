package com.difft.android.call.manager

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver



class CallCleanupManager(
    private val lifecycle: Lifecycle,
    private val context: Context,
) {

    




    fun cleanup(
        lifecycleObserver: CallLifecycleObserver?,
        dialogManager: CallDialogManager?,
        pictureInPictureManager: PictureInPictureManager?,
        screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver?,
        backPressedCallback: androidx.activity.OnBackPressedCallback?,
    ) {
        L.i { "[Call] CallCleanupManager cleanup start" }

        runCatching { cleanupLifecycleObserver(lifecycleObserver) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: cleanupLifecycleObserver failed" } }

        runCatching { cleanupUIResources(dialogManager, backPressedCallback) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: cleanupUIResources failed" } }

        runCatching { releaseManagers(pictureInPictureManager, screenUnlockBroadcastReceiver) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: releaseManagers failed" } }

        L.i { "[Call] CallCleanupManager cleanup end" }
    }

    private fun cleanupLifecycleObserver(observer: CallLifecycleObserver?) {
        observer?.let { lifecycle.removeObserver(it) }
    }

    private fun cleanupUIResources(
        dialogManager: CallDialogManager?,
        backPressedCallback: androidx.activity.OnBackPressedCallback?
    ) {
        dialogManager?.dismissAll()

        backPressedCallback?.let {
            if (it.isEnabled) {
                it.remove()
            }
        }
    }

    private fun releaseManagers(
        pictureInPictureManager: PictureInPictureManager?,
        screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver?
    ) {
        pictureInPictureManager?.release()
        screenUnlockBroadcastReceiver?.release(context)
    }
}
