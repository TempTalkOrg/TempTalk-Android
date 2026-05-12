package com.difft.android.chat.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.ForwardNoticeData.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Unit tests for [ForwardNoticeRenderer].
 *
 * Robolectric is used only for `Context.getQuantityString` / `Context.getString`; the
 * Renderer itself is pure. No `GlobalStaticMockRule` needed because `myId` is an explicit
 * parameter (by design — see Renderer doc).
 *
 * Locale is forced to en/zh per test so plurals text is deterministic across CI machines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ForwardNoticeRendererTest {

    private lateinit var context: Application
    private val myId = "ME"
    private val alice = "+10001"
    private val bob = "+10002"
    private val carol = "+10003"
    private val stranger = "+99999" // not in resolver map → hits fallback

    // Maps an id to a known display name so asserts can check the substring.
    private val knownNames = mapOf(
        alice to "Alice",
        bob to "Bob",
        carol to "Carol",
        myId to "SHOULD_NOT_BE_USED_FOR_ME" // myId path must return "You", not this
    )

    private val resolver: (String) -> String = { id ->
        knownNames[id] ?: "FALLBACK_$id"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Force English to get deterministic plurals ("one" vs "other" selection).
        forceLocale(Locale.ENGLISH)
    }

    private fun forceLocale(locale: Locale) {
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    // -------- 12 cases --------

    @Test
    fun `SINGLE count 1 — self is operator — uses You`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(alice), 1),
            context = context, resolveDisplayName = resolver
        )
        // English "one": "<op>" forwarded a message from <authors>.
        assertEquals("\"You\" forwarded a message from Alice.", result)
    }

    @Test
    fun `SINGLE count 1 — other is operator`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(bob), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded a message from Bob.", result)
    }

    @Test
    fun `ONE_BY_ONE count 3 — single author`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(bob), 3),
            context = context, resolveDisplayName = resolver
        )
        // English "other": "<op>" forwarded <count> messages from <authors>.
        assertEquals("\"Alice\" forwarded 3 messages from Bob.", result)
    }

    @Test
    fun `ONE_BY_ONE count 3 — three authors`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(alice, bob, carol), 3),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded 3 messages from Alice, Bob, Carol.", result)
    }

    @Test
    fun `COMBINED count 5 — multiple authors`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = bob, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, listOf(alice, bob), 5),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Bob\" forwarded 5 messages from Alice, Bob.", result)
    }

    @Test
    fun `SAVE_TO_NOTES — self is operator — multiple authors`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(Scene.SAVE_TO_NOTES, listOf(alice, bob, carol), 3),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" forwarded 3 messages from Alice, Bob, Carol.", result)
    }

    @Test
    fun `10 authors — truncated to first 3 with ellipsis, no trailing period`() {
        // IDs crafted to not collide with alice/bob/carol used elsewhere.
        val tenAuthors = (1..10).map { "+2000$it" }
        val tenResolver: (String) -> String = { id ->
            when (id) {
                alice -> "Alice"
                else -> "User${id.removePrefix("+2000")}"
            }
        }

        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, tenAuthors, 15),
            context = context,
            resolveDisplayName = tenResolver
        )

        // Only first 3 authors are rendered; ellipsis replaces the trailing period
        // (the sentence terminator is suppressed when the ellipsis already closes it).
        assertEquals("\"Alice\" forwarded 15 messages from User1, User2, User3...", result)
    }

    @Test
    fun `exactly 3 authors — no ellipsis, trailing period present`() {
        // Boundary: size == MAX_VISIBLE_AUTHORS (3). Condition is strictly greater than,
        // so 3 must render in full with a normal terminal period.
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, listOf(alice, bob, carol), 3),
            context = context,
            resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded 3 messages from Alice, Bob, Carol.", result)
    }

    @Test
    fun `4 authors — truncated to first 3 with ellipsis`() {
        // Just-over-boundary: size == 4 triggers truncation.
        val fourthId = "+20099"
        val fourAuthorResolver: (String) -> String = { id ->
            when (id) {
                alice -> "Alice"
                bob -> "Bob"
                carol -> "Carol"
                fourthId -> "Dave"
                else -> "FALLBACK_$id"
            }
        }
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, listOf(alice, bob, carol, fourthId), 4),
            context = context,
            resolveDisplayName = fourAuthorResolver
        )
        assertEquals("\"Alice\" forwarded 4 messages from Alice, Bob, Carol...", result)
    }

    @Test
    fun `repeated authors are de-duplicated before display and truncation`() {
        // Peer sends a list where the same author appears multiple times (common when
        // one person authored several of the forwarded messages). Display must use the
        // distinct set, not echo back "Alice, Alice, Alice...". Four raw ids but two
        // distinct authors → no truncation, no ellipsis.
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, listOf(alice, alice, alice, bob), 4),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded 4 messages from Alice, Bob.", result)
    }

    @Test
    fun `dedup runs before the display cap so repeats do not consume slots`() {
        // Raw list has 6 ids but only 4 distinct (alice, bob, carol, stranger).
        // Ellipsis must fire (4 > 3) and display the first 3 distinct names.
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED,
                listOf(alice, bob, alice, carol, bob, stranger),
                6
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(
            "\"Alice\" forwarded 6 messages from Alice, Bob, Carol...",
            result
        )
    }

    @Test
    fun `operator not in contacts — fallback used`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = stranger, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(alice), 1),
            context = context, resolveDisplayName = resolver
        )
        // Resolver returns "FALLBACK_<id>" for unknown ids (our test resolver).
        // Real callers pass `id.formatBase58Id()` — the Renderer is agnostic.
        assertEquals("\"FALLBACK_$stranger\" forwarded a message from Alice.", result)
    }

    @Test
    fun `author not in contacts — fallback used`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(stranger, bob), 2),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded 2 messages from FALLBACK_$stranger, Bob.", result)
    }

    @Test
    fun `empty sourceAuthorIds — authors block empty, no crash`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, emptyList(), 1),
            context = context, resolveDisplayName = resolver
        )
        // joinToString of emptyList == "" — renderer doesn't crash, output ends with "from ."
        assertEquals("\"Alice\" forwarded a message from .", result)
    }

    @Test
    fun `messageCount 0 — coerced to 1, no crash`() {
        // Business layer guarantees >= 1; Renderer's defensive coerceAtLeast(1)
        // means count=0 is rendered as count=1.
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(bob), 0),
            context = context, resolveDisplayName = resolver
        )
        // 0 → coerced to 1 → English "one" form selected.
        assertEquals("\"Alice\" forwarded a message from Bob.", result)
    }

    @Test
    fun `zh locale uses other form for any count`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(bob, carol), 5),
            context = context, resolveDisplayName = resolver
        )
        // 中文 plurals only has "other":  "X" 转发了 N 条来自 Y 的消息
        assertEquals("\"Alice\" 转发了 5 条来自 Bob, Carol 的消息。", result)

        // Also verify zh uses "您" for self-operator
        val self = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(alice), 1),
            context = context, resolveDisplayName = resolver
        )
        // count=1 → English picks "one" form, Chinese plurals ("other" only) picks "other"
        assertTrue("zh output should contain 您", self.contains("您"))
        assertTrue("zh output should contain Alice", self.contains("Alice"))
    }
}
