package com.difft.android.base.utils

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Permanent SharedPreferences carve-out for `SP_KEY_LANGUAGE`.
 *
 * Why a dedicated SP file (not DataStore):
 *   - `LanguageUtils.getLanguage(...)` is invoked from `TempTalkApplication.attachBaseContext()`,
 *     which runs **before** `Application.onCreate()`, **before** Hilt, **before** any
 *     AppStartup task. DataStore exposes only a suspending read API at that code position,
 *     so it is not usable here.
 *   - This carve-out is **permanent**: there is no v(N+3) phase that migrates
 *     `language_prefs.xml` to DataStore — the underlying ordering constraint is permanent.
 *
 * File layout:
 *   `context.dataDir/shared_prefs/language_prefs.xml` — ONE key, `"language"`, value is a
 *   BCP-47 language tag (e.g. `"en-US"`, `"zh-CN"`) OR the empty string `""` (sentinel for
 *   "fresh install, no migration needed → use system locale").
 *
 * Migration:
 *   On first read, if the new file does not contain `KEY_LANGUAGE`, we read the legacy
 *   value from `sp_chative_account` (via ["SP_KEY_LANGUAGE"]) and copy it
 *   over. Empty-string sentinel is written on fresh install so the migration check
 *   short-circuits on every subsequent cold start.
 */
object LanguageUtils {

    private const val PREF_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "language"

    // In-process locale cache. Populated by [getLanguage] / [getLanguageNonBlocking].
    private val localeCache = AtomicReference<Locale?>()
    private val cacheLock = Any()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /**
     * Provider for the legacy SP — swappable for unit tests. The legacy file is
     * `sp_chative_account` (the file that used to back the deleted `SharedPrefsUtil`).
     * Reads only — one-shot during the language-prefs carve-out migration.
     */
    @VisibleForTesting
    internal var legacyPrefsProvider: (Context) -> SharedPreferences = { ctx ->
        ctx.getSharedPreferences(LEGACY_SP_CHATIVE_ACCOUNT, Context.MODE_PRIVATE)
    }

    private const val LEGACY_SP_CHATIVE_ACCOUNT = "sp_chative_account"

    /**
     * Eagerly opens the `language_prefs` SP under double-checked locking so subsequent
     * reads on the same JVM instance do no disk I/O. Safe to call from `attachBaseContext`.
     */
    fun warmUpPreferences(context: Context) {
        if (cachedPrefs != null) return
        synchronized(cacheLock) {
            if (cachedPrefs != null) return
            cachedPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Returns the user-selected locale, or the system locale if no preference is set.
     *
     * Side effects (first call only):
     *   - Opens the `language_prefs` SP (warms `cachedPrefs`).
     *   - Runs one-shot migration from the legacy SP (`sp_chative_account` /
     *     ["SP_KEY_LANGUAGE"]). Migration is idempotent — presence of
     *     `KEY_LANGUAGE` in the new file short-circuits all subsequent runs.
     *
     * Migration failures (any [Throwable]) fall back to the system locale and are logged
     * at WARN level. Migration is never allowed to crash the application.
     */
    fun getLanguage(context: Context): Locale {
        localeCache.get()?.let { return it }
        synchronized(cacheLock) {
            localeCache.get()?.let { return it }

            val prefs = cachedPrefs
                ?: context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .also { cachedPrefs = it }

            try {
                migrateFromLegacyIfNeeded(context, prefs)
            } catch (t: Throwable) {
                L.w { "[LanguageUtils] migration failed; falling back to system locale: ${t.stackTraceToString()}" }
                val systemLocale = getSystemLocale(context)
                localeCache.set(systemLocale)
                return systemLocale
            }

            val langTag = prefs.getString(KEY_LANGUAGE, null)
            val result = if (langTag.isNullOrEmpty()) {
                getSystemLocale(context)
            } else {
                Locale.forLanguageTag(langTag)
            }
            localeCache.set(result)
            return result
        }
    }

    /**
     * Persists the user's locale choice. Updates the in-memory cache so subsequent
     * `getLanguage(...)` calls return the new value without re-reading disk.
     *
     * Uses `commit = true` because the typical caller (`LanguageFragment.onItemClick`)
     * immediately restarts the activity / process to apply the change — we cannot lose
     * the write to an async flush.
     */
    @SuppressLint("ApplySharedPref")
    fun saveLanguage(context: Context, locale: Locale) {
        val prefs = cachedPrefs
            ?: context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .also { cachedPrefs = it }
        prefs.edit(commit = true) {
            putString(KEY_LANGUAGE, locale.toLanguageTag())
        }
        localeCache.set(locale)
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

    /**
     * Used by `TempTalkApplication.attachBaseContext` and `BaseActivity.attachBaseContext`.
     *
     * Returns a [Context] whose [Configuration] is set to the user's locale with night-mode
     * cleared (we manage night-mode separately via [androidx.appcompat.app.AppCompatDelegate])
     * and font-scale pinned to 1.0 (we don't honor system font scaling — see existing UX
     * contract preserved from the prior implementation).
     *
     * From `Application.attachBaseContext` (the cold-start case), the locale cache is
     * empty and we cannot do disk I/O safely here — so we resolve via [getLanguage],
     * which is single-disk-read and bounded. `BaseActivity.attachBaseContext` runs after
     * Application.onCreate, where the `apply user locale` AppStartup step has already
     * populated the cache, so [getLanguage] is a cache hit.
     */
    fun createConfiguredContext(context: Context): Context {
        warmUpPreferences(context)
        val newLocale = getLanguage(context)
        val configuration = context.resources.configuration
        configuration.setLocale(newLocale)
        configuration.uiMode = configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_UNDEFINED
        configuration.fontScale = 1.0f
        return context.createConfigurationContext(configuration)
    }

    /**
     * Non-blocking, cache-only locale read for code that must not touch disk
     * (e.g. `attachBaseContext` on a slow device).
     *
     * Returns the cached locale if [getLanguage] has already run, otherwise falls back
     * to the system locale. Designed for the "two-step setup" where
     * `attachBaseContext` uses this, then `onCreate` runs [getLanguage] + [reapplyLocaleToAppResources]
     * to refresh the Application's resources once the user-selected locale is loaded.
     */
    fun getLanguageNonBlocking(): Locale = localeCache.get() ?: getSystemLocaleFallback()

    /**
     * Refreshes the [Application]'s [Configuration] with the cached locale. Used as the
     * `onCreate` half of the two-step setup — see [getLanguageNonBlocking].
     *
     * If the cache is still empty (warm-up did not complete), this is a no-op so we
     * don't override the system-default that `attachBaseContext` already applied.
     */
    @Suppress("DEPRECATION")
    fun reapplyLocaleToAppResources(app: Application) {
        val locale = localeCache.get() ?: return
        val resources = app.resources
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        Locale.setDefault(locale)
        L.i { "[LanguageUtils] reapplied locale: ${locale.toLanguageTag()}" }
    }

    // ----------------------------------------------------------------- migration

    /**
     * One-shot migration from the legacy `sp_chative_account` SP into `language_prefs`.
     *
     * Idempotency: presence-as-marker. `newPrefs.contains(KEY_LANGUAGE)` is the migration
     * marker — once any value (including the empty-string sentinel) is committed to the
     * new file, this method short-circuits forever.
     *
     * Process-kill safety: we commit to the new file FIRST and only remove from legacy
     * AFTER the new-file commit succeeds. A kill in between leaves the legacy value
     * orphaned but causes no data loss (subsequent boot sees the new-file value).
     */
    @SuppressLint("ApplySharedPref")
    private fun migrateFromLegacyIfNeeded(context: Context, newPrefs: SharedPreferences) {
        if (newPrefs.contains(KEY_LANGUAGE)) return  // presence-as-marker

        val legacyPrefs = legacyPrefsProvider(context)
        val legacyValue = legacyPrefs.getString("SP_KEY_LANGUAGE", null)

        if (legacyValue == null) {
            newPrefs.edit(commit = true) {
                putString(KEY_LANGUAGE, "")  // empty-string sentinel
            }
            L.i { "[LanguageUtils] migration: no legacy value; wrote empty sentinel" }
            return
        }

        val committed = newPrefs.edit().putString(KEY_LANGUAGE, legacyValue).commit()
        if (!committed) {
            L.w { "[LanguageUtils] migration: commit to new file failed (legacy preserved)" }
            return
        }
        legacyPrefs.edit().remove("SP_KEY_LANGUAGE").apply()
        L.i { "[LanguageUtils] migration: moved language from sp_chative_account to language_prefs" }
    }

    // ----------------------------------------------------------- system fallback

    private fun getSystemLocale(context: Context): Locale {
        return try {
            context.resources.configuration.locales[0]
        } catch (t: Throwable) {
            getSystemLocaleFallback()
        }
    }

    private fun getSystemLocaleFallback(): Locale = Locale.getDefault()

    // ----------------------------------------------------------------- test-only

    /**
     * Resets all in-memory caches. Test-only — never call from production code.
     */
    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(cacheLock) {
            cachedPrefs = null
            localeCache.set(null)
        }
    }
}

data class LanguageData(
    val name: String,
    val locale: Locale,
    var selected: Boolean = false
)
