package com.difft.android.chat.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.ForwardNoticeData.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * PRD coverage:
 *   §5.4.2 (12 forward variants by mode × count × useSelfOnly)
 *   §5.3.4 (locale-aware author list cap & overflow)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ForwardNoticeRendererTest {

    private lateinit var context: Application
    private val myId = "ME"
    private val alice = "+10001"
    private val bob = "+10002"
    private val carol = "+10003"
    private val dave = "+10004"
    private val eve = "+10005"
    private val frank = "+10006"
    private val stranger = "+99999" // not in resolver map → hits fallback

    // Maps an id to a known display name so asserts can check the substring.
    private val knownNames = mapOf(
        alice to "Alice",
        bob to "Bob",
        carol to "Carol",
        dave to "Dave",
        eve to "Eve",
        frank to "Frank",
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

    // -------- Backwards-compatible UNKNOWN mode (existing behavior, English) --------

    @Test
    fun `UNKNOWN SINGLE count 1 — self is operator — uses You`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(alice), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" forwarded a message from Alice.", result)
    }

    @Test
    fun `UNKNOWN SINGLE count 1 — other is operator`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(bob), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded a message from Bob.", result)
    }

    @Test
    fun `UNKNOWN ONE_BY_ONE count 3 — single author`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(bob), 3),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded 3 messages from Bob.", result)
    }

    @Test
    fun `UNKNOWN ONE_BY_ONE count 3 — three authors — Oxford comma`() {
        // English 3 authors → "A, B, and C" (Oxford comma per PRD §5.3.4)
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(alice, bob, carol), 3),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded 3 messages from Alice, Bob, and Carol.", result)
    }

    @Test
    fun `UNKNOWN — two authors use 'and' connector, no Oxford comma`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = bob, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, listOf(alice, bob), 5),
            context = context, resolveDisplayName = resolver
        )
        // English 2 authors → "A and B" (no comma)
        assertEquals("\"Bob\" forwarded 5 messages from Alice and Bob.", result)
    }

    @Test
    fun `UNKNOWN SAVE_TO_NOTES — self is operator — multiple authors`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(Scene.SAVE_TO_NOTES, listOf(alice, bob, carol), 3),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" forwarded 3 messages from Alice, Bob, and Carol.", result)
    }

    // -------- 5-author cap & overflow (PRD §5.3.4) --------

    @Test
    fun `exactly 5 authors — all spelled out, Oxford comma`() {
        // Boundary: size == MAX_VISIBLE_AUTHORS (5). All names should be rendered.
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, listOf(alice, bob, carol, dave, eve), 5),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(
            "\"Alice\" forwarded 5 messages from Alice, Bob, Carol, Dave, and Eve.",
            result
        )
    }

    @Test
    fun `6 authors — first 5 shown plus 'and 1 other' (singular)`() {
        // Just-over-boundary: 6 distinct authors. Overflow N = total - shown.size = 1 → singular.
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED,
                listOf(alice, bob, carol, dave, eve, frank),
                6
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(
            "\"Alice\" forwarded 6 messages from Alice, Bob, Carol, Dave, Eve and 1 other.",
            result
        )
    }

    @Test
    fun `10 authors — first 5 plus 'and 5 others'`() {
        val tenAuthors = (1..10).map { "+2000$it" }
        val tenResolver: (String) -> String = { id ->
            if (id == alice) "Alice" else "User${id.removePrefix("+2000")}"
        }

        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, tenAuthors, 15),
            context = context,
            resolveDisplayName = tenResolver
        )

        // First 5 spelled, overflow = 10 - 5 = 5 others. Locale terminator appended.
        assertEquals(
            "\"Alice\" forwarded 15 messages from User1, User2, User3, User4, User5 and 5 others.",
            result
        )
    }

    @Test
    fun `dedup runs before the display cap so repeats do not consume slots`() {
        // Raw list has 6 ids but only 4 distinct (alice, bob, carol, stranger).
        // 4 ≤ 5, so no overflow — full Oxford-comma form.
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
            "\"Alice\" forwarded 6 messages from Alice, Bob, Carol, and FALLBACK_$stranger.",
            result
        )
    }

    @Test
    fun `repeated authors are de-duplicated — single distinct survives`() {
        // 4 raw ids, 2 distinct, all under cap.
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, listOf(alice, alice, alice, bob), 4),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded 4 messages from Alice and Bob.", result)
    }

    // -------- Fallback paths --------

    @Test
    fun `operator not in contacts — fallback used`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = stranger, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(alice), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"FALLBACK_$stranger\" forwarded a message from Alice.", result)
    }

    @Test
    fun `author not in contacts — fallback used`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(stranger, bob), 2),
            context = context, resolveDisplayName = resolver
        )
        // Two authors → "A and B" form.
        assertEquals("\"Alice\" forwarded 2 messages from FALLBACK_$stranger and Bob.", result)
    }

    // -------- Defensive paths --------

    @Test
    fun `empty sourceAuthorIds — authors block empty, no crash`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, emptyList(), 1),
            context = context, resolveDisplayName = resolver
        )
        // SourceListFormatter returns "" for empty input — output ends with "from ."
        assertEquals("\"Alice\" forwarded a message from .", result)
    }

    @Test
    fun `messageCount 0 — coerced to 1, no crash`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(bob), 0),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded a message from Bob.", result)
    }

    // -------- PRD §5.4.3.2 cases 17–28 (Forward by mode) --------

    @Test
    fun `case 17 — UNKNOWN count 1 — others`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.SINGLE, listOf(bob), 1, CombinedForwardMode.UNKNOWN
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded a message from Bob.", result)
    }

    @Test
    fun `case 18 — UNKNOWN count 1 — self only`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(
                Scene.SINGLE, listOf(myId), 1, CombinedForwardMode.UNKNOWN
            ),
            context = context, resolveDisplayName = resolver
        )
        // PRD v2.0: all-self source list (outer authors) → short form, no redundant "from You".
        assertEquals("\"You\" forwarded a message.", result)
    }

    @Test
    fun `case 19 — ALL_COMBINED_FORWARD count 1 — others`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED, listOf(bob), 1, CombinedForwardMode.ALL_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded a chat history from Bob.", result)
    }

    @Test
    fun `case 20 — ALL_COMBINED_FORWARD count 1 — self only`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED, listOf(myId), 1, CombinedForwardMode.ALL_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" forwarded a chat history.", result)
    }

    @Test
    fun `case 21 — UNKNOWN count 5 — others`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.ONE_BY_ONE,
                listOf(alice, bob, carol, dave, eve),
                5,
                CombinedForwardMode.UNKNOWN,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(
            "\"Alice\" forwarded 5 messages from Alice, Bob, Carol, Dave, and Eve.",
            result
        )
    }

    @Test
    fun `case 22 — UNKNOWN count 5 — self only`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(
                Scene.ONE_BY_ONE,
                listOf(myId, myId, myId, myId, myId),
                5,
                CombinedForwardMode.UNKNOWN,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" forwarded 5 messages.", result)
    }

    @Test
    fun `case 23 — ALL_COMBINED_FORWARD count 3 — others — chat_history plural`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED,
                listOf(alice, bob, carol),
                3,
                CombinedForwardMode.ALL_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(
            "\"Alice\" forwarded 3 chat histories from Alice, Bob, and Carol.",
            result
        )
    }

    @Test
    fun `case 24 — ALL_COMBINED_FORWARD count 3 — self only`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED,
                listOf(myId),
                3,
                CombinedForwardMode.ALL_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" forwarded 3 chat histories.", result)
    }

    @Test
    fun `case 25 — CONTAINS_COMBINED_FORWARD count 5 — others — mixed plural`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED,
                listOf(alice, bob, carol, dave, eve),
                5,
                CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(
            "\"Alice\" forwarded 5 messages (including chat history) from Alice, Bob, Carol, Dave, and Eve.",
            result
        )
    }

    @Test
    fun `case 26 — CONTAINS_COMBINED_FORWARD count 5 — self only`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED,
                listOf(myId),
                5,
                CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" forwarded 5 messages (including chat history).", result)
    }

    @Test
    fun `case 27 — SUB_COMBINED_FORWARD count 1 — others`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.SINGLE,
                listOf(bob),
                1,
                CombinedForwardMode.SUB_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded a message from Bob's chat history.", result)
    }

    @Test
    fun `case 28 — SUB_COMBINED_FORWARD count 1 — self only`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(
                Scene.SINGLE,
                listOf(myId),
                1,
                CombinedForwardMode.SUB_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" forwarded a message from a chat history.", result)
    }

    // -------- CONTAINS_COMBINED_FORWARD count == 1 (single bubble IS a CF) --------

    @Test
    fun `CONTAINS count 1 — single CF bubble — uses chat_history single`() {
        // Per PRD §5.4.2: count == 1 with CONTAINS means that lone bubble is a CF.
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.SINGLE,
                listOf(bob),
                1,
                CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded a chat history from Bob.", result)
    }

    // -------- Chinese locale --------

    @Test
    fun `zh — UNKNOWN — uses 、 separator, no 'and'`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(bob, carol), 5),
            context = context, resolveDisplayName = resolver
        )
        // PRD §5.3.4: Chinese uses 、 only — no 和 connector.
        assertEquals("\"Alice\" 转发了 5 条来自 Bob、Carol 的消息。", result)
        assertFalse("zh must NOT use 和 connector", result.contains("和"))
    }

    @Test
    fun `zh — 8 authors — 等 N 人 overflow (N = total, with space)`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val eightAuthors = (1..8).map { "+3000$it" }
        val r: (String) -> String = { id ->
            if (id == alice) "Alice" else "U${id.removePrefix("+3000")}"
        }
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, eightAuthors, 12),
            context = context, resolveDisplayName = r
        )
        // First 5 names shown, suffix "等 8 人" (N = total authors = 8).
        assertEquals(
            "\"Alice\" 转发了 12 条来自 U1、U2、U3、U4、U5 等 8 人 的消息。",
            result
        )
    }

    @Test
    fun `zh — ALL_COMBINED_FORWARD count 3 — chat_history plural`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED, listOf(bob, carol), 3, CombinedForwardMode.ALL_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" 转发了 3 条来自 Bob、Carol 的聊天记录。", result)
    }

    @Test
    fun `zh — CONTAINS count 5 — mixed plural`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.COMBINED,
                listOf(bob, carol),
                5,
                CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" 转发了 5 条来自 Bob、Carol 的消息（含聊天记录）。", result)
    }

    @Test
    fun `zh — SUB_COMBINED_FORWARD count 1 — others`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(
                Scene.SINGLE, listOf(bob), 1, CombinedForwardMode.SUB_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" 转发了 1 条来自 Bob 聊天记录中的消息。", result)
    }

    @Test
    fun `zh — self uses 您`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val self = ForwardNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, listOf(alice), 1),
            context = context, resolveDisplayName = resolver
        )
        assertTrue("zh output should contain 您", self.contains("您"))
        assertTrue("zh output should contain Alice", self.contains("Alice"))
    }
}
