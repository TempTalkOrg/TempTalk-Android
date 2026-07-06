package com.difft.android.chat.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Unit tests for [SourceListFormatter] — locale-aware author list formatting per
 * PRD v1.0 §5.3.4.
 *
 * Robolectric only needed because the formatter reads `Context.resources.configuration.locales`.
 * The formatter itself is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SourceListFormatterTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun forceLocale(locale: Locale) {
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    // ---------- English ----------

    @Test
    fun `english — empty list returns empty string`() {
        forceLocale(Locale.ENGLISH)
        assertEquals("", SourceListFormatter.format(emptyList(), context))
    }

    @Test
    fun `english — 1 name — just the name`() {
        forceLocale(Locale.ENGLISH)
        assertEquals("Alice", SourceListFormatter.format(listOf("Alice"), context))
    }

    @Test
    fun `english — 2 names — A and B, no Oxford comma`() {
        forceLocale(Locale.ENGLISH)
        assertEquals(
            "Alice and Bob",
            SourceListFormatter.format(listOf("Alice", "Bob"), context)
        )
    }

    @Test
    fun `english — 3 names — Oxford comma`() {
        forceLocale(Locale.ENGLISH)
        assertEquals(
            "Alice, Bob, and Carol",
            SourceListFormatter.format(listOf("Alice", "Bob", "Carol"), context)
        )
    }

    @Test
    fun `english — 4 names — Oxford comma`() {
        forceLocale(Locale.ENGLISH)
        assertEquals(
            "Alice, Bob, Carol, and Dave",
            SourceListFormatter.format(listOf("Alice", "Bob", "Carol", "Dave"), context)
        )
    }

    @Test
    fun `english — 5 names — all spelled, Oxford comma`() {
        forceLocale(Locale.ENGLISH)
        assertEquals(
            "Alice, Bob, Carol, Dave, and Eve",
            SourceListFormatter.format(
                listOf("Alice", "Bob", "Carol", "Dave", "Eve"),
                context
            )
        )
    }

    @Test
    fun `english — 6 names — 5 shown plus 'and 1 other' (singular)`() {
        forceLocale(Locale.ENGLISH)
        // Overflow: total=6, shown.size=5, others=1 → singular noun "other".
        assertEquals(
            "Alice, Bob, Carol, Dave, Eve and 1 other",
            SourceListFormatter.format(
                listOf("Alice", "Bob", "Carol", "Dave", "Eve", "Frank"),
                context
            )
        )
    }

    @Test
    fun `english — 10 names — 5 shown plus 'and 5 others' (plural)`() {
        forceLocale(Locale.ENGLISH)
        val names = (1..10).map { "User$it" }
        // Overflow: total=10, shown.size=5, others=5 → plural noun "others".
        assertEquals(
            "User1, User2, User3, User4, User5 and 5 others",
            SourceListFormatter.format(names, context)
        )
    }

    // ---------- Chinese ----------

    @Test
    fun `chinese — empty list returns empty string`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        assertEquals("", SourceListFormatter.format(emptyList(), context))
    }

    @Test
    fun `chinese — 1 name`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        assertEquals("Alice", SourceListFormatter.format(listOf("Alice"), context))
    }

    @Test
    fun `chinese — 2 names — joined with 、 (no 和)`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        assertEquals(
            "Alice、Bob",
            SourceListFormatter.format(listOf("Alice", "Bob"), context)
        )
    }

    @Test
    fun `chinese — 3 names — joined with 、`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        assertEquals(
            "Alice、Bob、Carol",
            SourceListFormatter.format(listOf("Alice", "Bob", "Carol"), context)
        )
    }

    @Test
    fun `chinese — 5 names — fully spelled`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        assertEquals(
            "Alice、Bob、Carol、Dave、Eve",
            SourceListFormatter.format(
                listOf("Alice", "Bob", "Carol", "Dave", "Eve"),
                context
            )
        )
    }

    @Test
    fun `chinese — 6 names — 5 shown plus 等 6 人 (N is total)`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        // PRD §5.3.4: Chinese overflow "等 N 人" — space between N and 人.
        assertEquals(
            "Alice、Bob、Carol、Dave、Eve 等 6 人",
            SourceListFormatter.format(
                listOf("Alice", "Bob", "Carol", "Dave", "Eve", "Frank"),
                context
            )
        )
    }

    @Test
    fun `chinese — 10 names — 5 shown plus 等 10 人`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val names = (1..10).map { "U$it" }
        assertEquals(
            "U1、U2、U3、U4、U5 等 10 人",
            SourceListFormatter.format(names, context)
        )
    }

    // ---------- Locale fallback ----------

    @Test
    fun `non-English non-Chinese locale falls back to English formatting`() {
        // PRD §5.3.4 only specifies Chinese vs English; any other locale uses English.
        forceLocale(Locale.FRENCH)
        assertEquals(
            "Alice, Bob, and Carol",
            SourceListFormatter.format(listOf("Alice", "Bob", "Carol"), context)
        )
    }

    @Test
    fun `traditional chinese locale also uses chinese formatting`() {
        forceLocale(Locale.TRADITIONAL_CHINESE)
        assertEquals(
            "Alice、Bob",
            SourceListFormatter.format(listOf("Alice", "Bob"), context)
        )
    }
}
