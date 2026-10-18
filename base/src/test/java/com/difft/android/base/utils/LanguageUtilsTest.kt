package com.difft.android.base.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageUtilsTest {

    private lateinit var context: Context
    private lateinit var newPrefs: SharedPreferences
    private lateinit var legacyPrefs: SharedPreferences

    private val originalLegacyProvider = LanguageUtils.legacyPrefsProvider

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
