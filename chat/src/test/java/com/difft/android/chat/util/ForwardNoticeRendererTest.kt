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
    private val stranger = "+99999" 

    
    private val knownNames = mapOf(
        alice to "Alice",
        bob to "Bob",
        carol to "Carol",
        dave to "Dave",
        eve to "Eve",
        frank to "Frank",
        myId to "SHOULD_NOT_BE_USED_FOR_ME" 
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

    

    @Test
    fun `exactly 5 authors — all spelled out, Oxford comma`() {
        
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

        
        assertEquals(
            "\"Alice\" forwarded 15 messages from User1, User2, User3, User4, User5 and 5 others.",
            result
        )
    }

    @Test
    fun `dedup runs before the display cap so repeats do not consume slots`() {
        
        
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
        
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.COMBINED, listOf(alice, alice, alice, bob), 4),
            context = context, resolveDisplayName = resolver
        )
        assertEquals("\"Alice\" forwarded 4 messages from Alice and Bob.", result)
    }

    

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
        
        assertEquals("\"Alice\" forwarded 2 messages from FALLBACK_$stranger and Bob.", result)
    }

    

    @Test
    fun `empty sourceAuthorIds — authors block empty, no crash`() {
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.SINGLE, emptyList(), 1),
            context = context, resolveDisplayName = resolver
        )
        
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

    

    @Test
    fun `CONTAINS count 1 — single CF bubble — uses chat_history single`() {
        
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

    

    @Test
    fun `zh — UNKNOWN — uses 、 separator, no 'and'`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val result = ForwardNoticeRenderer.render(
            operatorId = alice, myId = myId,
            notice = ForwardNoticeData(Scene.ONE_BY_ONE, listOf(bob, carol), 5),
            context = context, resolveDisplayName = resolver
        )
        
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
