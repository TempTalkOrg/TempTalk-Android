package com.difft.android.chat.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.MessageActivityNoticeData
import difft.android.messageserialization.model.MessageActivityNoticeData.Type
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Unit tests for [MessageActivityNoticeRenderer].
 *
 * Locale forced per test for deterministic plurals selection.
 *
 * PRD coverage:
 *   §5.4.1 (copy variants — main-conv UNKNOWN/CONTAINS/ALL collapse; SUB diverges)
 *   §5.3.4 (locale-aware author list cap & overflow)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageActivityNoticeRendererTest {

    private lateinit var context: Application
    private val myId = "ME"
    private val alice = "+10001"
    private val bob = "+10002"
    private val carol = "+10003"
    private val dave = "+10004"
    private val eve = "+10005"

    private val knownNames = mapOf(
        alice to "Alice",
        bob to "Bob",
        carol to "Carol",
        dave to "Dave",
        eve to "Eve",
        myId to "SHOULD_NOT_BE_USED_FOR_ME"  // myId path must return "You"
    )

    private val resolver: (String) -> String = { id ->
        knownNames[id] ?: "FALLBACK_$id"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        forceLocale(Locale.ENGLISH)
    }

    private fun forceLocale(locale: Locale) {
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    // ---------- Basic COPY rendering (UNKNOWN mode = legacy callers) ----------

    @Test
    fun `COPY count 1 — self is operator and only author — self-only variant`() {
        // PRD v2.0: when the source list (OUTER authors) is all-self, drop the redundant "from You".
        // Reachable e.g. when I copy my own single-forward bubble whose inner is someone else's:
        // gating (inner author) traces it, but the source list (outer author = me) → short form.
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(myId), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied a message.", result)
    }

    @Test
    fun `COPY count 1 — self is operator, other is author`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(alice), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied a message from Alice.", result)
    }

    @Test
    fun `COPY count 1 — other is operator and author`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(bob), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied a message from Bob.", result)
    }

    @Test
    fun `COPY count 3 — single other author`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(bob), 3),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied 3 messages from Bob.", result)
    }

    @Test
    fun `COPY count 3 — three authors — Oxford comma`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(alice, bob, carol), 3),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied 3 messages from Alice, Bob, and Carol.", result)
    }

    @Test
    fun `COPY — two authors use 'and' connector, no Oxford comma`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(bob, carol), 2),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied 2 messages from Bob and Carol.", result)
    }

    // ---------- Self-only shortcut conditions ----------

    @Test
    fun `self-only — operator is me but author is someone else — full form not self-only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(myId), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied a message from You.", result)
    }

    @Test
    fun `self-only — operator is me, authors mixed me + other — NOT self-only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(myId, alice), 2),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied 2 messages from You and Alice.", result)
    }

    // ---------- 5-author cap & overflow ----------

    @Test
    fun `exactly 5 authors — all spelled out`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(alice, bob, carol, dave, eve), 5),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(
            "\"Alice\" copied 5 messages from Alice, Bob, Carol, Dave, and Eve.",
            result
        )
    }

    @Test
    fun `10 authors — first 5 plus 'and 5 others'`() {
        val tenAuthors = (1..10).map { "+2000$it" }
        val tenResolver: (String) -> String = { id ->
            if (id == alice) "Alice" else "User${id.removePrefix("+2000")}"
        }
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, tenAuthors, 15),
            context = context, resolveDisplayName = tenResolver
        )
        assertEquals(
            "\"Alice\" copied 15 messages from User1, User2, User3, User4, User5 and 5 others.",
            result
        )
    }

    @Test
    fun `duplicated authors deduped before truncation count`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(bob, bob, bob), 3),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied 3 messages from Bob.", result)
    }

    // ---------- Defensive coercion ----------

    @Test
    fun `messageCount 0 coerced to 1`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(bob), 0),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied a message from Bob.", result)
    }

    // ---------- PRD §5.4.1 mode × useSelfOnly matrix ----------

    @Test
    fun `case 1 — UNKNOWN count 1 — others`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(bob), 1, CombinedForwardMode.UNKNOWN
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied a message from Bob.", result)
    }

    @Test
    fun `case 2 — UNKNOWN count 1 — self only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId), 1, CombinedForwardMode.UNKNOWN
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied a message.", result)
    }

    @Test
    fun `case 3 — CONTAINS count 1 — others — collapses to main-conv key`() {
        // Per PRD §5.4.1: main-conv copy (UNKNOWN/CONTAINS/ALL) all use chat_copy_notice.
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(bob), 1, CombinedForwardMode.CONTAINS_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied a message from Bob.", result)
    }

    @Test
    fun `case 4 — CONTAINS count 1 — self only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId), 1, CombinedForwardMode.CONTAINS_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied a message.", result)
    }

    @Test
    fun `case 5 — ALL count 1 — others — collapses to main-conv key`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(bob), 1, CombinedForwardMode.ALL_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied a message from Bob.", result)
    }

    @Test
    fun `case 6 — ALL count 1 — self only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId), 1, CombinedForwardMode.ALL_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied a message.", result)
    }

    @Test
    fun `case 7 — SUB count 1 — others — diverges`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(bob), 1, CombinedForwardMode.SUB_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied a message from Bob's chat history.", result)
    }

    @Test
    fun `case 8 — SUB count 1 — self only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId), 1, CombinedForwardMode.SUB_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied a message from a chat history.", result)
    }

    @Test
    fun `case 9 — UNKNOWN count 5 — others`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(alice, bob, carol, dave, eve), 5, CombinedForwardMode.UNKNOWN
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(
            "\"Alice\" copied 5 messages from Alice, Bob, Carol, Dave, and Eve.",
            result
        )
    }

    @Test
    fun `case 10 — UNKNOWN count 5 — self only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId, myId, myId), 5, CombinedForwardMode.UNKNOWN
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied 5 messages.", result)
    }

    @Test
    fun `case 11 — CONTAINS count 5 — others — main-conv collapse`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY,
                listOf(bob, carol),
                5,
                CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied 5 messages from Bob and Carol.", result)
    }

    @Test
    fun `case 12 — CONTAINS count 5 — self only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId), 5, CombinedForwardMode.CONTAINS_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied 5 messages.", result)
    }

    @Test
    fun `case 13 — ALL count 3 — others — main-conv collapse`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY,
                listOf(bob, carol),
                3,
                CombinedForwardMode.ALL_COMBINED_FORWARD,
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied 3 messages from Bob and Carol.", result)
    }

    @Test
    fun `case 14 — ALL count 3 — self only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId), 3, CombinedForwardMode.ALL_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied 3 messages.", result)
    }

    @Test
    fun `case 15 — SUB count 3 — others — Mac-only but Android renders sensibly`() {
        // Per PRD §5.4.3.1 detail-view multi-copy is Mac only; Android still selects
        // a valid plural key (from_chat_history `other`) for safety.
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(bob), 3, CombinedForwardMode.SUB_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" copied 3 messages from Bob's chat history.", result)
    }

    @Test
    fun `case 16 — SUB count 3 — self only`() {
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId), 3, CombinedForwardMode.SUB_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"You\" copied 3 messages from a chat history.", result)
    }

    // ---------- Locale: Chinese ----------

    @Test
    fun `chinese locale — UNKNOWN — uses 、 separator`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(bob, carol), 2),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" 复制了 2 条来自 Bob、Carol 的消息。", result)
    }

    @Test
    fun `chinese locale — SUB count 1 — others`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = MessageActivityNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(bob), 1, CombinedForwardMode.SUB_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" 复制了 1 条来自 Bob 聊天记录中的消息。", result)
    }

    @Test
    fun `chinese locale — SUB self-only`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(
                Type.COPY, listOf(myId), 1, CombinedForwardMode.SUB_COMBINED_FORWARD
            ),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"您\" 复制了 1 条来自聊天记录中的消息。", result)
    }

    @Test
    fun `chinese locale — self-only main-conv`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = MessageActivityNoticeRenderer.render(
            operatorId = myId, myId = myId,
            notice = MessageActivityNoticeData(Type.COPY, listOf(myId), 1),
            context = context, resolveDisplayName = resolver
        )
        assertEquals(true, result.contains("复制了"))
        assertEquals(true, result.endsWith("条消息。"))
    }
}
