package com.difft.android.selector.language

import java.util.Locale

object LocaleTransform {
    @JvmStatic
    fun getLanguage(language: Int): Locale {
        return when (language) {
            LanguageConfig.ENGLISH ->
                Locale.ENGLISH
            LanguageConfig.TRADITIONAL_CHINESE ->
                Locale.TRADITIONAL_CHINESE
            LanguageConfig.KOREA ->
                Locale.KOREA
            LanguageConfig.GERMANY ->
                Locale.GERMANY
            LanguageConfig.FRANCE ->
                Locale.FRANCE
            LanguageConfig.JAPAN ->
                Locale.JAPAN
            LanguageConfig.VIETNAM ->
                Locale("vi")
            LanguageConfig.SPANISH ->
                Locale("es", "ES")
            LanguageConfig.PORTUGAL ->
                Locale("pt", "PT")
            LanguageConfig.AR ->
                Locale("ar", "AE")
            LanguageConfig.RU ->
                Locale("ru", "rRU")
            LanguageConfig.CS ->
                Locale("cs", "rCZ")
            LanguageConfig.KK ->
                Locale("kk", "rKZ")
            else ->
                Locale.CHINESE
        }
    }
}
