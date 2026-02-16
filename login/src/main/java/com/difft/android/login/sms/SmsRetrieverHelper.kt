package com.difft.android.login.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * Helper class for SMS Retriever API to automatically retrieve SMS verification codes.
 *
 * SMS Format requirement:
 * The SMS must contain:
 * 1. Start with "<#>" prefix
 * 2. The verification code
 * 3. End with 11-character app hash
 *
 * Example SMS format:
 * <#> Your verification code is: 123456
 * FA+9qCX9VSu
 *
 * Usage:
 * ```
 * val helper = SmsRetrieverHelper(context) { code ->
 *     // Auto-fill the code
 *     editText.setText(code)
 * }
 * helper.startListening()
 *
 * // When done (e.g., in onDestroy)
 * helper.stopListening()
 * ```
 */
class SmsRetrieverHelper(
    private val context: Context,
    private val onCodeReceived: (String) -> Unit
) {
    private var smsReceiver: BroadcastReceiver? = null

    /**
     * Start listening for SMS messages using SMS Retriever API.
     * This method is safe to call even if Google Play Services is not available.
     */
    fun startListening() {
        L.i { "[SmsRetriever] Attempting to start" }
        try {
            val client = SmsRetriever.getClient(context)
            val task = client.startSmsRetriever()

            task.addOnSuccessListener {
                L.i { "[SmsRetriever] Started successfully" }
                registerReceiver()
            }

            task.addOnFailureListener { e ->
                L.e { "[SmsRetriever] Failed to start: ${e.message}" }
            }
        } catch (e: Exception) {
            // GMS not available or other initialization error
            L.e { "[SmsRetriever] Not available: ${e.javaClass.simpleName}" }
        }
    }

    /**
     * Stop listening for SMS messages and unregister the receiver.
     */
    fun stopListening() {
        unregisterReceiver()
    }

    private fun registerReceiver() {
        if (smsReceiver != null) {
            return
        }

        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (SmsRetriever.SMS_RETRIEVED_ACTION == intent?.action) {
                    val extras = intent.extras
                    val status = extras?.get(SmsRetriever.EXTRA_STATUS) as? Status

                    when (status?.statusCode) {
                        CommonStatusCodes.SUCCESS -> {
                            val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE)
                            L.i { "[SmsRetriever] SMS received" }
                            message?.let { extractCode(it) }
                        }
                        CommonStatusCodes.TIMEOUT -> {
                            L.i { "[SmsRetriever] SMS retrieval timed out" }
                        }
                        else -> {
                            L.w { "[SmsRetriever] SMS retrieval failed with status: ${status?.statusCode}" }
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        ContextCompat.registerReceiver(
            context,
            smsReceiver,
            intentFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        L.i { "[SmsRetriever] Broadcast receiver registered" }
    }

    private fun unregisterReceiver() {
        smsReceiver?.let {
            try {
                context.unregisterReceiver(it)
                L.i { "[SmsRetriever] Broadcast receiver unregistered" }
            } catch (e: Exception) {
                L.w { "[SmsRetriever] Failed to unregister receiver: ${e.message}" }
            }
        }
        smsReceiver = null
    }

    /**
     * Extract the verification code from the SMS message.
     * Supports multiple common OTP formats:
     * - 4-8 digit codes
     * - Codes after common prefixes like "code is:", "code:", "验证码", etc.
     */
    private fun extractCode(message: String) {
        // Try to extract 6-digit code (most common)
        val code = extractDigitCode(message, 6)
            ?: extractDigitCode(message, 4)
            ?: extractDigitCode(message, 8)

        if (code != null) {
            L.i { "[SmsRetriever] Code extracted successfully" }
            onCodeReceived(code)
        } else {
            L.i { "[SmsRetriever] Could not extract code from message" }
        }
    }

    /**
     * Extract a digit code of specific length from the message.
     * Uses negative lookahead/lookbehind to handle various punctuation correctly.
     */
    private fun extractDigitCode(message: String, length: Int): String? {
        // Pattern to match exactly N consecutive digits not surrounded by other digits
        val pattern = "(?<![0-9])(\\d{$length})(?![0-9])".toRegex()
        return pattern.find(message)?.groupValues?.get(1)
    }

    companion object {
        /**
         * Generate the app hash required for SMS Retriever.
         * This should be called during development to get the hash.
         * The hash depends on the app's package name and signing certificate.
         *
         * For debug builds, you can use this helper to generate the hash.
         * For release builds, you need to generate it using the release keystore.
         */
        fun getAppHash(context: Context): String {
            return AppSignatureHelper(context).getAppSignatures().firstOrNull() ?: ""
        }
    }
}