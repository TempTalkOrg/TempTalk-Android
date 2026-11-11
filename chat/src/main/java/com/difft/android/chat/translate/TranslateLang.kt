package com.difft.android.chat.translate

import com.difft.android.chat.translate.backend.TranslateLanguageCodes







enum class TranslateLang(val setting: Int, val code: String) {
    ZH(1, TranslateLanguageCodes.ZH),
    EN(2, TranslateLanguageCodes.EN);

    companion object {
        
        fun fromSetting(value: Int): TranslateLang? = entries.firstOrNull { it.setting == value }
    }
}








@JvmInline
value class ModelId(val code: String)









enum class TranslateMode { AUTO, MANUAL }


sealed interface TranslateResult {
    data class Translated(val text: String, val sourceCode: String) : TranslateResult

    
    data object SameLanguage : TranslateResult

    
    data class Unsupported(val reason: Reason) : TranslateResult

    
    data class ModelNotReady(val missing: Set<ModelId>) : TranslateResult

    data class Failed(val cause: Throwable) : TranslateResult

    enum class Reason { EMPTY, TOO_LONG, LANGUAGE_NOT_SUPPORTED }
}
