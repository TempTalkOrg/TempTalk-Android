package org.thoughtcrime.securesms.util

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import com.difft.android.base.log.lumberjack.L

/**
 * BroadcastReceiver that clears the clipboard after a timeout.
 * Used to automatically remove sensitive content from the clipboard.
 */
class ClearClipboardAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ClearClipboardAlarm"

        /**
         * Special label used to identify clipboard content copied by our app.
         * This allows us to avoid clearing content copied by other apps.
         */
        const val CLIPBOARD_LABEL = "difft_sensitive_copy"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val clipboard = ServiceUtil.getClipboardManager(context)

        try {
            val label = clipboard.primaryClip?.description?.label?.toString()

            // Only skip if we can clearly identify it's from another app
            if (label != null && label != CLIPBOARD_LABEL) {
                L.i { "[$TAG] Skipping clear: content from another app" }
                return
            }
        } catch (e: Exception) {
            // Android 10+ may fail to read clipboard in background, proceed with clear
            L.w { "[ClearClipboardAlarmReceiver] failed to read clipboard: ${e.stackTraceToString()}" }
        }

        // Clear the clipboard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", " "))
        }

        L.i { "[$TAG] Clipboard cleared" }
    }
}