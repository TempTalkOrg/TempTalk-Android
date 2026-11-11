package com.difft.android.chat.util

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import com.difft.android.base.log.lumberjack.L





class ClearClipboardAlarmReceiver : android.content.BroadcastReceiver() {

    companion object {
        private const val TAG = "ClearClipboardAlarm"

        



        const val CLIPBOARD_LABEL = "difft_sensitive_copy"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val clipboard = ServiceUtil.getClipboardManager(context)

        try {
            val label = clipboard.primaryClip?.description?.label?.toString()

            
            if (label != null && label != CLIPBOARD_LABEL) {
                L.i { "[$TAG] Skipping clear: content from another app" }
                return
            }
        } catch (e: Exception) {
            
            L.w { "[ClearClipboardAlarmReceiver] failed to read clipboard: ${e.stackTraceToString()}" }
        }

        
        
        
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", " "))
            }
            L.i { "[$TAG] Clipboard cleared" }
        } catch (e: Exception) {
            L.w { "[$TAG] Failed to clear clipboard: ${e.stackTraceToString()}" }
        }
    }
}