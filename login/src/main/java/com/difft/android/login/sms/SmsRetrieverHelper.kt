package com.difft.android.login.sms

import android.content.Context
import com.difft.android.base.log.lumberjack.L





class SmsRetrieverHelper(
    private val context: Context,
    private val onCodeReceived: (String) -> Unit
) {
    fun startListening() {
        L.d { "[SmsRetriever] Not available in this build" }
    }

    fun stopListening() {}
}