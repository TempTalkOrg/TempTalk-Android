package com.difft.android.selector.language

import android.content.Context
import android.content.res.Resources
import com.difft.android.selector.utils.SpUtils
import java.util.Locale

/**
 * PictureLanguageUtils
 */
object PictureLanguageUtils {

    private const val KEY_LOCALE = "KEY_LOCALE"
    private const val VALUE_FOLLOW_SYSTEM = "VALUE_FOLLOW_SYSTEM"

    /**
     * init app the language
     */
    @JvmStatic
    fun setAppLanguage(context: Context, languageId: Int, defaultLanguageId: Int) {
        if (languageId >= 0) {
            applyLanguage(context, LocaleTransform.getLanguage(languageId))
        } else {
            if (defaultLanguageId >= 0) {
                applyLanguage(context, LocaleTransform.getLanguage(defaultLanguageId))
            } else {
                setDefaultLanguage(context)
            }
        }
    }

    /**
     * Apply the language.
     */
    private fun applyLanguage(context: Context, locale: Locale) {
        applyLanguage(context, locale, false)
    }

    private fun applyLanguage(context: Context, locale: Locale, isFollowSystem: Boolean) {
        if (isFollowSystem) {
            SpUtils.putString(context, KEY_LOCALE, VALUE_FOLLOW_SYSTEM)
        } else {
            val localLanguage = locale.language
            val localCountry = locale.country
            SpUtils.putString(context, KEY_LOCALE, localLanguage + "\$" + localCountry)
        }
        updateLanguage(context, locale)
    }

    private fun updateLanguage(context: Context, locale: Locale) {
        val resources = context.resources
        val config = resources.configuration
        val contextLocale = config.locale
        if (equals(contextLocale.language, locale.language)
            && equals(contextLocale.country, locale.country)
        ) {
            return
        }
        val dm = resources.displayMetrics
        config.setLocale(locale)
        context.createConfigurationContext(config)
        resources.updateConfiguration(config, dm)
    }

    /**
     * set default language
     */
    private fun setDefaultLanguage(context: Context) {
        val resources: Resources = context.resources
        val config = resources.configuration
        val dm = resources.displayMetrics
        config.setLocale(Locale.getDefault())
        context.createConfigurationContext(config)
        resources.updateConfiguration(config, dm)
    }

    private fun equals(s1: CharSequence?, s2: CharSequence?): Boolean {
        if (s1 === s2) return true
        if (s1 != null && s2 != null && s1.length == s2.length) {
            if (s1 is String && s2 is String) {
                return s1 == s2
            } else {
                for (i in 0 until s1.length) {
                    if (s1[i] != s2[i]) return false
                }
                return true
            }
        }
        return false
    }
}
