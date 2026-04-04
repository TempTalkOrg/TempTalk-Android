package com.difft.android.chat.translate

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 翻译管理器（F-Droid 构建版本）
 * ML Kit translate 依赖 Google Play Services，在此构建中不可用。
 */
@Singleton
class TranslateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun translateText(
        text: String,
        targetLang: String,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        L.d { "[TranslateManager] Translation not available in this build" }
        onFailure(UnsupportedOperationException("Translation not available in this build"))
    }

    fun close() {}
}