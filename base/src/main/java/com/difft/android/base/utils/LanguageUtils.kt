package com.difft.android.base.utils

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.icu.util.ULocale
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference







object LanguageUtils {

    private const val PREF_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "language"

    
    
    private val explicitCache = AtomicReference<Locale?>()

    @Volatile
    private var resolved = false
    private val cacheLock = Any()

    




    private val SUPPORTED_LOCALE_TAGS = arrayOf("en", "zh-CN")

    




    @VisibleForTesting
    internal var systemLocalesProvider: () -> LocaleList = { Resources.getSystem().configuration.locales }

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    
    @VisibleForTesting
    internal var legacyPrefsProvider: (Context) -> SharedPreferences = { ctx ->
        ctx.getSharedPreferences(LEGACY_SP_CHATIVE_ACCOUNT, Context.MODE_PRIVATE)
    }

    private const val LEGACY_SP_CHATIVE_ACCOUNT = "sp_chative_account"

    



    fun warmUpPreferences(context: Context) {
        if (cachedPrefs != null) return
        synchronized(cacheLock) {
            if (cachedPrefs != null) return
            cachedPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    













    fun getLanguage(context: Context): Locale = explicitLanguage(context) ?: systemLocale()

    
    private fun explicitLanguage(context: Context): Locale? {
        if (resolved) return explicitCache.get()
        synchronized(cacheLock) {
            if (resolved) return explicitCache.get()

            val prefs = cachedPrefs
                ?: context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .also { cachedPrefs = it }

            val explicit = try {
                migrateFromLegacyIfNeeded(context, prefs)
                prefs.getString(KEY_LANGUAGE, null)?.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)
            } catch (t: Throwable) {
                L.w { "[LanguageUtils] migration failed; following the system locale: ${t.stackTraceToString()}" }
                null
            }
            explicitCache.set(explicit)
            resolved = true
            return explicit
        }
    }

    







    @SuppressLint("ApplySharedPref")
    fun saveLanguage(context: Context, locale: Locale) {
        val prefs = cachedPrefs
            ?: context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .also { cachedPrefs = it }
        prefs.edit(commit = true) {
            putString(KEY_LANGUAGE, locale.toLanguageTag())
        }
        explicitCache.set(locale)
        resolved = true
    }

    






    fun appLocaleFor(locale: Locale): Locale {
        if (locale.language != Locale.CHINA.language) return Locale.US
        val script = locale.script.ifEmpty { ULocale.addLikelySubtags(ULocale.forLocale(locale)).script }
        return if (script == "Hans") Locale.CHINA else Locale.US
    }

    





    fun isCurrentChoice(context: Context, locale: Locale): Boolean {
        val current = getLanguage(context)
        return current.language == locale.language && appLocaleFor(current) == locale
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

    





    fun createConfiguredContext(context: Context, densityDpi: Int = Configuration.DENSITY_DPI_UNDEFINED): Context {
        warmUpPreferences(context)
        
        val override = Configuration()
        explicitLanguage(context)?.let { override.setLocale(it) }
        override.fontScale = 1.0f
        
        
        if (densityDpi != Configuration.DENSITY_DPI_UNDEFINED) override.densityDpi = densityDpi
        return context.createConfigurationContext(override)
    }

    








    fun getLanguageNonBlocking(): Locale = explicitCache.get() ?: systemLocale()

    






    @Suppress("DEPRECATION")
    fun reapplyLocaleToAppResources(app: Application) {
        val locale = explicitCache.get() ?: return
        val resources = app.resources
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        Locale.setDefault(locale)
        L.i { "[LanguageUtils] reapplied locale: ${locale.toLanguageTag()}" }
    }

    

    










    @SuppressLint("ApplySharedPref")
    private fun migrateFromLegacyIfNeeded(context: Context, newPrefs: SharedPreferences) {
        if (newPrefs.contains(KEY_LANGUAGE)) return  

        val legacyPrefs = legacyPrefsProvider(context)
        val legacyValue = legacyPrefs.getString("SP_KEY_LANGUAGE", null)

        if (legacyValue == null) {
            newPrefs.edit(commit = true) {
                putString(KEY_LANGUAGE, "")  
            }
            L.i { "[LanguageUtils] migration: no legacy value; wrote empty sentinel" }
            return
        }

        val committed = newPrefs.edit().putString(KEY_LANGUAGE, legacyValue).commit()
        if (!committed) {
            L.w { "[LanguageUtils] migration: commit to new file failed (legacy preserved)" }
            return
        }
        legacyPrefs.edit { remove("SP_KEY_LANGUAGE") }
        L.i { "[LanguageUtils] migration: moved language from sp_chative_account to language_prefs" }
    }

    

    




    private fun systemLocale(): Locale = try {
        systemLocalesProvider().getFirstMatch(SUPPORTED_LOCALE_TAGS) ?: Locale.getDefault()
    } catch (t: Throwable) {
        L.w { "[LanguageUtils] system locale read failed error=${t.javaClass.simpleName}" }
        Locale.getDefault()
    }

    

    


    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(cacheLock) {
            cachedPrefs = null
            explicitCache.set(null)
            resolved = false
        }
    }
}

data class LanguageData(
    val name: String,
    val locale: Locale,
    val selected: Boolean = false
)
