package com.difft.android.base.utils

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import com.difft.android.base.R
import java.util.Locale

object LanguageUtils {

    @Volatile
    private var cachedLocale: Locale? = null

    fun getLanguage(context: Context): Locale {
        cachedLocale?.let { return it }

        synchronized(this) {
            cachedLocale?.let { return it }

            val langTag = context.getSharedPreferences(
                SharedPrefsUtil.SHARED_PREFS_NAME, Context.MODE_PRIVATE
            ).getString(SharedPrefsUtil.SP_KEY_LANGUAGE, null)

            val result = langTag?.let { Locale.forLanguageTag(it) }
                ?: context.resources.configuration.locales[0]

            cachedLocale = result
            return result
        }
    }

    fun saveLanguage(context: Context, locale: Locale) {
        context.getSharedPreferences(SharedPrefsUtil.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putString(SharedPrefsUtil.SP_KEY_LANGUAGE, locale.toLanguageTag())
            }
        cachedLocale = locale
    }

    fun getLanguageList(context: Context): List<LanguageData> {
        val selectedLocale = getLanguage(context)
        return listOf(
            LanguageData(
                ResUtils.getString(R.string.language_english),
                Locale.US,
                selected = selectedLocale.language == Locale.US.language
            ),
            LanguageData(
                ResUtils.getString(R.string.language_chinese),
                Locale.CHINA,
                selected = selectedLocale.language == Locale.CHINA.language
            )
        )
    }

    fun getLanguageName(context: Context): String {
        return when (getLanguage(context).language) {
            Locale.CHINA.language -> context.getString(R.string.language_chinese)
            else -> context.getString(R.string.language_english)
        }
    }

    fun createConfiguredContext(context: Context): Context {
        val newLocale = getLanguage(context)
        val configuration = context.resources.configuration
        configuration.setLocale(newLocale)
        configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_UNDEFINED
        configuration.fontScale = 1.0f
        return context.createConfigurationContext(configuration)
    }
}

data class LanguageData(
    val name: String,
    val locale: Locale,
    var selected: Boolean = false
)
