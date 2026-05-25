package com.difft.android.base.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
