package com.difft.android.selector.basic

import android.content.Context
import android.content.ContextWrapper
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.language.PictureLanguageUtils

open class PictureContextWrapper(base: Context) : ContextWrapper(base) {

    override fun getSystemService(name: String): Any? {
        if (Context.AUDIO_SERVICE == name) {
            return applicationContext.getSystemService(name)
        }
        return super.getSystemService(name)
    }

    companion object {
        @JvmStatic
        fun wrap(context: Context, language: Int, defaultLanguage: Int): ContextWrapper {
            if (language != LanguageConfig.UNKNOWN_LANGUAGE) {
                PictureLanguageUtils.setAppLanguage(context, language, defaultLanguage)
            }
            return PictureContextWrapper(context)
        }
    }
}
