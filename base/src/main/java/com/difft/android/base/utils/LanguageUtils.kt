package com.difft.android.base.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import com.difft.android.base.R
import java.util.Locale
import androidx.core.content.edit
import com.difft.android.base.log.lumberjack.L

object LanguageUtils {

    var locale: Locale? = null

    fun getLanguageList(context: Context): List<LanguageData> {
        val selectedLocale = getLanguage(context)
        return mutableListOf<LanguageData>().apply {
            this.add(LanguageData(ResUtils.getString(R.string.language_english), Locale.US, selectedLocale.language == Locale.US.language))
            this.add(LanguageData(ResUtils.getString(R.string.language_chinese), Locale.CHINA, selectedLocale.language == Locale.CHINA.language))
        }
    }

    fun saveLanguage(context: Context, locale: Locale) {
        context.getSharedPreferences(SharedPrefsUtil.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putString(SharedPrefsUtil.SP_KEY_LANGUAGE, locale.toLanguageTag())
            }
    }

    fun getLanguage(context: Context): Locale {
        return locale ?: context.getSharedPreferences(SharedPrefsUtil.SHARED_PREFS_NAME, Context.MODE_PRIVATE).getString(SharedPrefsUtil.SP_KEY_LANGUAGE, null)?.let {
            Locale.forLanguageTag(it)
        } ?: getSystemLocale(context)
    }

    fun getLanguageName(context: Context): String {
        return when (getLanguage(context).language) {
            Locale.CHINA.language -> {
                context.getString(R.string.language_chinese)
            }

            else -> {
                context.getString(R.string.language_english)
            }
        }
    }

    private fun getSystemLocale(context: Context): Locale {
        return context.resources.configuration.locales[0]
//        return Locale.getDefault()
    }

    fun updateBaseContextLocale(context: Context): Context {
        val newLocale = locale ?: getLanguage(context)
        val configuration = context.resources.configuration
        configuration.setLocale(newLocale)
        configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_UNDEFINED
        configuration.fontScale = 1.0f //不跟随系统字体缩放
        return context.createConfigurationContext(configuration)
    }

}

data class LanguageData(
    val name: String,
    val locale: Locale,
    var selected: Boolean = false
)