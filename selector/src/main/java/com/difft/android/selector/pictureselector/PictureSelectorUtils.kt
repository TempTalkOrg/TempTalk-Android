package com.difft.android.selector.pictureselector

import android.content.Context
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.style.PictureSelectorStyle

object PictureSelectorUtils {
    fun getSelectorStyle(context: Context): PictureSelectorStyle {
        val selectorStyle = PictureSelectorStyle()
        return selectorStyle
    }

    fun getLanguage(context: Context): Int {
        val locale = LanguageUtils.getLanguage(context)
        return when (locale.language) {
            "zh" -> LanguageConfig.CHINESE
            "en" -> LanguageConfig.ENGLISH
            else -> LanguageConfig.ENGLISH
        }
    }
}