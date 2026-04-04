package com.difft.android.login.sms

import android.content.Context
import com.difft.android.base.log.lumberjack.L

/**
 * SMS Retriever Helper（F-Droid 构建版本）
 * SMS Retriever API 依赖 Google Play Services，在此构建中不可用。
 * 用户需手动输入验证码。
 */
class SmsRetrieverHelper(
    private val context: Context,
    private val onCodeReceived: (String) -> Unit
) {
    fun startListening() {
        L.d { "[SmsRetriever] Not available in this build" }
    }

    fun stopListening() {}
}