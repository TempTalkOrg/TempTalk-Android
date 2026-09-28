package com.difft.android.chat.translate.backend

/**
 * The language codes the feature names explicitly. BCP-47, which is what every [TranslateBackend]
 * takes and returns.
 */
object TranslateLanguageCodes {
    const val ZH = "zh"
    const val EN = "en"

    /**
     * The language every pair is translated through. A backend is required to have it available
     * without a download, so a source in this language is never gated on model readiness.
     */
    const val PIVOT = EN
}
