package com.difft.android.login.sms

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.difft.android.base.log.lumberjack.L

/**
 * Helper class to monitor clipboard for OTP codes.
 *
 * This is useful for Chinese market where:
 * 1. Most phones don't have GMS (SMS Retriever won't work)
 * 2. System automatically copies verification codes to clipboard
 * 3. Users expect auto-fill from clipboard
 *
 * Usage:
 * ```
 * val helper = ClipboardOtpHelper(context) { code ->
 *     editText.setText(code)
 * }
 * helper.startListening()
 *
 * // When done (e.g., in onDestroy)
 * helper.stopListening()
 * ```
 */
class ClipboardOtpHelper(
    private val context: Context,
    private val codeLength: Int = 6,
    private val onCodeDetected: (String) -> Unit
) {
    private var clipboardManager: ClipboardManager? = null
    private var isListening = false
    // Time when listening started, to ignore old clipboard content
    private var startTime: Long = 0

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboard()
    }

    /**
     * Start monitoring clipboard for OTP codes.
     */
    fun startListening() {
        if (isListening) return

        // Use application context to avoid activity memory leak
        clipboardManager = context.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        isListening = true
        startTime = System.currentTimeMillis()
        L.i { "[ClipboardOtp] Started listening" }
    }

    /**
     * Stop monitoring clipboard.
     */
    fun stopListening() {
        if (!isListening) return

        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        clipboardManager = null
        isListening = false
        L.i { "[ClipboardOtp] Stopped listening" }
    }

    /**
     * Check clipboard for OTP code.
     * Called by listener when clipboard changes, and by onResume to detect
     * codes copied while app was in background.
     */
    fun checkClipboard() {
        if (!isListening) return

        // Check if clipboard content is newer than when we started listening
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val clipTimestamp = clipboardManager?.primaryClip?.description?.timestamp ?: 0
            if (clipTimestamp < startTime) {
                L.i { "[ClipboardOtp] Clipboard content is old, skipping" }
                return
            }
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

    /**
     * Extract OTP code from text.
     * Handles various formats:
     * - Pure digits: "123456"
     * - With text: "验证码是 123456"
     * - With Chinese punctuation: "验证码：123456，请在5分钟内使用"
     * - With brackets: "【App】123456"
     */
    private fun extractCode(text: String): String? {
        // If the text is exactly N digits, use it directly
        if (text.matches(Regex("^\\d{$codeLength}$"))) {
            return text
        }

        // Find N consecutive digits not surrounded by other digits
        // Uses lookahead/lookbehind to handle Chinese punctuation correctly
        val pattern = "(?<![0-9])(\\d{$codeLength})(?![0-9])".toRegex()
        return pattern.find(text)?.groupValues?.get(1)
    }
}