package com.difft.android.login.sms

import android.content.ClipboardManager
import android.content.Context
import com.difft.android.base.log.lumberjack.L





class ClipboardOtpHelper(
    private val context: Context,
    private val codeLength: Int = 6,
    private val onCodeDetected: (String) -> Unit
) {
    private var clipboardManager: ClipboardManager? = null
    private var isListening = false
    
    private var startTime: Long = 0

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboard()
    }

    


    fun startListening() {
        if (isListening) return

        
        clipboardManager = context.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        isListening = true
        startTime = System.currentTimeMillis()
        L.i { "[ClipboardOtp] Started listening" }
    }

    


    fun stopListening() {
        if (!isListening) return

        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        clipboardManager = null
        isListening = false
        L.i { "[ClipboardOtp] Stopped listening" }
    }

    




    fun checkClipboard() {
        if (!isListening) return

        
        val clipTimestamp = clipboardManager?.primaryClip?.description?.timestamp ?: 0
        if (clipTimestamp < startTime) {
            L.i { "[ClipboardOtp] Clipboard content is old, skipping" }
            return
        }

        val currentText = getCurrentClipboardText()
        if (currentText.isNullOrBlank()) return

        val code = extractCode(currentText)
        if (code != null) {
            L.i { "[ClipboardOtp] OTP code detected" }
            onCodeDetected(code)
        }
    }

    private fun getCurrentClipboardText(): String? {
        return try {
            clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            L.i { "[ClipboardOtp] Failed to read clipboard" }
            null
        }
    }

    







    private fun extractCode(text: String): String? {
        
        if (text.matches(Regex("^\\d{$codeLength}$"))) {
            return text
        }

        
        
        val pattern = "(?<![0-9])(\\d{$codeLength})(?![0-9])".toRegex()
        return pattern.find(text)?.groupValues?.get(1)
    }
}