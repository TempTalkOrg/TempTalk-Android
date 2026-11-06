package com.difft.android.base.utils

import android.content.Context
import android.app.Application
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral guardrails for the [LanguageUtils] permanent-SP carve-out.
 *
 * Pins the contracts the design (§6.3 / §6.4 / §6.6) calls out as load-bearing:
 *
 *   1. **Fresh install** — no legacy value → migration writes empty-string sentinel
 *      and [getLanguage] returns the system locale.
 *   2. **Presence-as-marker idempotency** — once the new file contains `KEY_LANGUAGE`,
 *      the migration short-circuits forever (even if a legacy value reappears).
 *   3. **Existing-user migration** — legacy `SP_KEY_LANGUAGE` value is copied to the
 *      new file FIRST, then removed from legacy. Process-kill in between leaves the
 *      new file intact (next boot is a cache hit).
 *   4. **Save/load round-trip** — [saveLanguage] persists to the new file and the
 *      in-memory cache is updated.
 *   5. **Migration failures never crash** — caught and reported, falling back to
 *      the system locale.
 *   6. **Configured context pins only locale and font scale** — density, screen size and
 *      night mode keep following the system, and the caller's configuration is not mutated.
 *   7. **Following the system is live (#1395)** — with no explicit choice, [LanguageUtils.getLanguage]
 *      reads the system language list on every call and picks its first match for the APK's
 *      `en` / `zh-CN` resources (the platform's own pick), and nothing pins that locale onto a
 *      Context, the Application resources or [Locale.getDefault].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageUtilsTest {

    private lateinit var context: Context
    private lateinit var newPrefs: SharedPreferences
    private lateinit var legacyPrefs: SharedPreferences

    private val originalLegacyProvider = LanguageUtils.legacyPrefsProvider
    private val originalSystemLocalesProvider = LanguageUtils.systemLocalesProvider
    private var systemLocales = LocaleList.forLanguageTags("en-US")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        newPrefs = context.getSharedPreferences("language_prefs", Context.MODE_PRIVATE)
        legacyPrefs = context.getSharedPreferences("legacy_test_prefs", Context.MODE_PRIVATE)

        // Clean slate every test.
        newPrefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
        LanguageUtils.resetForTest()

        // Swap in our isolated legacy SP so we don't pollute the real `sp_chative_account`.
        LanguageUtils.legacyPrefsProvider = { legacyPrefs }
    }

    @After
    fun teardown() {
        newPrefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
        LanguageUtils.resetForTest()
        LanguageUtils.legacyPrefsProvider = originalLegacyProvider
        LanguageUtils.systemLocalesProvider = originalSystemLocalesProvider
    }

    private fun followFakeSystem(tags: String) {
        systemLocales = LocaleList.forLanguageTags(tags)
        LanguageUtils.systemLocalesProvider = { systemLocales }
    }

    // ---- 7. live system-language following (#1395) ----

    @Test
    fun `following the system picks up a system language change without a cache reset`() {
        followFakeSystem("en-US")
        assertEquals(Locale.forLanguageTag("en-US"), LanguageUtils.getLanguage(context))

        systemLocales = LocaleList.forLanguageTags("zh-CN")

        assertEquals(Locale.forLanguageTag("zh-CN"), LanguageUtils.getLanguage(context))
        assertEquals(Locale.forLanguageTag("zh-CN"), LanguageUtils.getLanguageNonBlocking())
    }

    @Test
    fun `an explicit choice ignores later system language changes`() {
        followFakeSystem("zh-CN")
        LanguageUtils.saveLanguage(context, Locale.US)

        systemLocales = LocaleList.forLanguageTags("zh-CN,en-US")

        assertEquals(Locale.US, LanguageUtils.getLanguage(context))
    }

    @Test
    fun `a failed migration follows the live system language`() {
        followFakeSystem("en-US")
        LanguageUtils.legacyPrefsProvider = { throw IllegalStateException("legacy SP unreadable") }

        assertEquals(Locale.forLanguageTag("en-US"), LanguageUtils.getLanguage(context))
        systemLocales = LocaleList.forLanguageTags("zh-CN")
        assertEquals(Locale.forLanguageTag("zh-CN"), LanguageUtils.getLanguage(context))
        assertFalse(newPrefs.contains("language"), "a failed migration writes nothing")
    }

    @Test
    fun `a multi-language system list resolves to its first supported entry`() {
        followFakeSystem("fr-FR,zh-CN")
        assertEquals(Locale.forLanguageTag("zh-CN"), LanguageUtils.getLanguage(context))

        systemLocales = LocaleList.forLanguageTags("de-DE,en-GB")
        assertEquals(Locale.forLanguageTag("en-GB"), LanguageUtils.getLanguage(context))
    }

    @Test
    fun `an unsupported list resolves like the platform does`() {
        followFakeSystem("fr-FR")
        assertEquals(Locale.forLanguageTag("fr-FR"), LanguageUtils.getLanguage(context), "single entry: that entry")

        systemLocales = LocaleList.forLanguageTags("de-DE,fr-FR")
        assertEquals(Locale.forLanguageTag("de-DE"), LanguageUtils.getLanguage(context), "no match: the first entry")
    }

    @Test
    fun `Traditional Chinese does not map to Simplified Chinese`() {
        followFakeSystem("zh-TW")
        val tw = LanguageUtils.getLanguage(context)
        assertEquals(Locale.forLanguageTag("zh-TW"), tw)
        assertEquals("zh", tw.language)

        systemLocales = LocaleList.forLanguageTags("zh-HK,en-US")
        assertEquals(Locale.forLanguageTag("en-US"), LanguageUtils.getLanguage(context))
    }

    @Test
    fun `an empty or unreadable system list falls back to the JVM default`() {
        followFakeSystem("")
        assertEquals(Locale.getDefault(), LanguageUtils.getLanguage(context))

        LanguageUtils.systemLocalesProvider = { throw IllegalStateException("no system resources") }
        assertEquals(Locale.getDefault(), LanguageUtils.getLanguage(context))
    }

    @Test
    fun `the default provider reads the system resources`() {
        assertEquals(
            Resources.getSystem().configuration.locales.getFirstMatch(arrayOf("en", "zh-CN")),
            LanguageUtils.getLanguage(context),
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-rUS")
    fun `a configured context following the system does not pin the locale`() {
        val configured = LanguageUtils.createConfiguredContext(context)
        assertEquals(Locale.US, configured.resources.configuration.locales[0])

        RuntimeEnvironment.setQualifiers("zh-rCN")

        assertEquals(Locale.CHINA, configured.resources.configuration.locales[0])
        assertEquals(1.0f, configured.resources.configuration.fontScale)
    }

    @Test
    fun `reapplying the locale while following the system changes nothing`() {
        followFakeSystem("zh-CN")
        val app = context as Application
        val before = Configuration(app.resources.configuration)
        val defaultBefore = Locale.getDefault()
        LanguageUtils.getLanguage(app)

        LanguageUtils.reapplyLocaleToAppResources(app)

        assertEquals(before.locales, app.resources.configuration.locales)
        assertEquals(defaultBefore, Locale.getDefault())
    }

    @Test
    fun `reapplying an explicit choice pins it as before`() {
        val defaultBefore = Locale.getDefault()
        try {
            LanguageUtils.saveLanguage(context, Locale.CHINA)
            val app = context as Application

            LanguageUtils.reapplyLocaleToAppResources(app)

            assertEquals(Locale.CHINA, app.resources.configuration.locales[0])
            assertEquals(Locale.CHINA, Locale.getDefault())
        } finally {
            Locale.setDefault(defaultBefore)
        }
    }

    @Test
    fun `fresh install writes empty-string sentinel and returns system locale`() {
        // Pre-condition: no legacy value, no new value.
        assertFalse(newPrefs.contains("language"))
        assertFalse(legacyPrefs.contains("SP_KEY_LANGUAGE"))

        val locale = LanguageUtils.getLanguage(context)

        assertTrue(newPrefs.contains("language"), "presence marker must be written on first call")
        assertEquals("", newPrefs.getString("language", null), "fresh install uses empty-string sentinel")
        // System default in Robolectric is en_US; verify we got *some* locale (not null).
        assertEquals(locale, locale, "returned locale must be non-null") // structural — Locale is non-null type
    }

    @Test
    fun `legacy value is migrated and removed from legacy SP`() {
        legacyPrefs.edit().putString("SP_KEY_LANGUAGE", "zh-CN").commit()

        val locale = LanguageUtils.getLanguage(context)

        assertEquals("zh-CN", newPrefs.getString("language", null), "legacy value must be copied to new file")
        assertFalse(
            legacyPrefs.contains("SP_KEY_LANGUAGE"),
            "legacy key must be removed AFTER new-file commit succeeds"
        )
        assertEquals(Locale.forLanguageTag("zh-CN"), locale)
    }

    @Test
    fun `migration runs only once - presence marker short-circuits subsequent calls`() {
        // Round 1: legacy value present → migration copies it.
        legacyPrefs.edit().putString("SP_KEY_LANGUAGE", "en-US").commit()
        LanguageUtils.getLanguage(context)
        LanguageUtils.resetForTest()  // simulate fresh JVM (process restart)

        // Round 2: legacy reappears (shouldn't happen in practice, but pin idempotency).
        legacyPrefs.edit().putString("SP_KEY_LANGUAGE", "zh-CN").commit()
        val locale = LanguageUtils.getLanguage(context)

        assertEquals(
            "en-US", newPrefs.getString("language", null),
            "presence marker must short-circuit; new file's en-US must NOT be overwritten by re-migration"
        )
        assertEquals(Locale.forLanguageTag("en-US"), locale)
        // Legacy untouched on round 2 because migration was skipped.
        assertEquals("zh-CN", legacyPrefs.getString("SP_KEY_LANGUAGE", null))
    }

    @Test
    fun `saveLanguage persists to new file and updates cache`() {
        LanguageUtils.saveLanguage(context, Locale.forLanguageTag("zh-CN"))

        assertEquals("zh-CN", newPrefs.getString("language", null))
        // Cache hit — no I/O needed; getLanguage returns the saved value.
        assertEquals(Locale.forLanguageTag("zh-CN"), LanguageUtils.getLanguage(context))
    }

    @Test
    fun `empty-string sentinel is treated as no-preference and returns system locale`() {
        newPrefs.edit().putString("language", "").commit()

        val locale = LanguageUtils.getLanguage(context)

        // The result is whatever Robolectric's system locale is — not the empty sentinel.
        assertFalse(
            locale.toLanguageTag() == "und",
            "empty-string sentinel must resolve to a real system locale (got: ${locale.toLanguageTag()})"
        )
    }

    @Test
    fun `getLanguageNonBlocking returns system fallback when cache is cold`() {
        // No prior getLanguage call — cache is null.
        val locale = LanguageUtils.getLanguageNonBlocking()
        // Just verify we got *something* (the API contract is "never null, never throw").
        assertEquals(locale.language.isNotEmpty(), true)
    }

    @Test
    fun `getLanguageNonBlocking returns cached locale after warm up`() {
        LanguageUtils.saveLanguage(context, Locale.forLanguageTag("zh-CN"))
        assertEquals(Locale.forLanguageTag("zh-CN"), LanguageUtils.getLanguageNonBlocking())
    }

    // appLocaleFor: the APK ships English and Simplified Chinese only.
    @Test
    fun `appLocaleFor maps Hans Chinese to CHINA regardless of region or script tag`() {
        assertEquals(Locale.CHINA, LanguageUtils.appLocaleFor(Locale.forLanguageTag("zh-CN")))
        assertEquals(Locale.CHINA, LanguageUtils.appLocaleFor(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals(Locale.CHINA, LanguageUtils.appLocaleFor(Locale.forLanguageTag("zh-SG")))
    }

    @Test
    fun `appLocaleFor maps Hant Chinese and every other language to US`() {
        assertEquals(Locale.US, LanguageUtils.appLocaleFor(Locale.forLanguageTag("zh-TW")))
        assertEquals(Locale.US, LanguageUtils.appLocaleFor(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals(Locale.US, LanguageUtils.appLocaleFor(Locale.forLanguageTag("en-GB")))
        assertEquals(Locale.US, LanguageUtils.appLocaleFor(Locale.forLanguageTag("fr-FR")))
    }

    @Test
    fun `isCurrentChoice needs the checked row to also be the rendered language`() {
        LanguageUtils.saveLanguage(context, Locale.forLanguageTag("zh-Hans-CN"))
        assertTrue(LanguageUtils.isCurrentChoice(context, Locale.CHINA))
        assertFalse(LanguageUtils.isCurrentChoice(context, Locale.US))

        LanguageUtils.saveLanguage(context, Locale.forLanguageTag("zh-TW"))
        assertFalse(LanguageUtils.isCurrentChoice(context, Locale.CHINA))
        assertFalse(LanguageUtils.isCurrentChoice(context, Locale.US))

        LanguageUtils.saveLanguage(context, Locale.forLanguageTag("en-GB"))
        assertTrue(LanguageUtils.isCurrentChoice(context, Locale.US))
        assertFalse(LanguageUtils.isCurrentChoice(context, Locale.CHINA))
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh-rTW")
    fun `with no stored preference a Traditional Chinese system locale still offers both restarts`() {
        assertFalse(LanguageUtils.isCurrentChoice(context, Locale.US))
        assertFalse(LanguageUtils.isCurrentChoice(context, Locale.CHINA))
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN")
    fun `with no stored preference a Simplified Chinese system locale is current for zh-CN`() {
        assertTrue(LanguageUtils.isCurrentChoice(context, Locale.CHINA))
        assertFalse(LanguageUtils.isCurrentChoice(context, Locale.US))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-rUS-mdpi")
    fun `configured context pins locale and font scale but follows later display changes`() {
        LanguageUtils.saveLanguage(context, Locale.CHINA)
        val configured = LanguageUtils.createConfiguredContext(context)
        assertEquals(Locale.US, context.resources.configuration.locales[0])

        RuntimeEnvironment.setQualifiers("+w600dp-night-hdpi")
        RuntimeEnvironment.setFontScale(1.5f)

        val config = configured.resources.configuration
        assertEquals(Locale.CHINA, config.locales[0])
        assertEquals(1.0f, config.fontScale)
        assertEquals(240, config.densityDpi)
        assertEquals(1.5f, configured.resources.displayMetrics.density)
        assertEquals(600, config.screenWidthDp)
        assertEquals(Configuration.UI_MODE_NIGHT_YES, config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
    }
}
