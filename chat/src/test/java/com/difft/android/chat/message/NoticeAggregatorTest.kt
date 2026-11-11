package com.difft.android.chat.message

import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.TextMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test








@Suppress("LargeClass")
class NoticeAggregatorTest {

    private val alice = "+10001"
    private val bob = "+10002"
    private val carol = "+10003"
    private val me = "+10000"

    private fun mkForward(author: String): Forward = Forward(
        id = 0L,
        type = 0,
        isFromGroup = false,
        author = author,
        text = "t",
        attachments = null,
        forwards = null,
        mentions = null,
    )

    
    private fun regularMsg(
        authorId: String,
        timeStamp: Long = 0L,
    ): TextChatMessage = TextChatMessage().apply {
        this.id = "msg-$authorId-$timeStamp"
        this.authorId = authorId
        this.timeStamp = timeStamp
    }

    
    private fun cfMsg(
        authorId: String,
        timeStamp: Long = 0L,
        innerCount: Int = 2,
    ): TextChatMessage = TextChatMessage().apply {
        require(innerCount > 1) { "CF requires > 1 inner forwards" }
        this.id = "cf-$authorId-$timeStamp"
        this.authorId = authorId
        this.timeStamp = timeStamp
        this.forwardContext = ForwardContext(
            forwards = (0 until innerCount).map { mkForward("+inner$it") },
            isFromGroup = false,
        )
    }

    
    private fun singleForwardMsg(
        authorId: String,
        timeStamp: Long = 0L,
    ): TextChatMessage = TextChatMessage().apply {
        this.id = "single-fwd-$authorId-$timeStamp"
        this.authorId = authorId
        this.timeStamp = timeStamp
        this.forwardContext = ForwardContext(
            forwards = listOf(mkForward("+inner0")),
            isFromGroup = false,
        )
    }
    

    @Test
    fun `isCombinedForward — regular text message returns false`() {
        assertEquals(false, regularMsg(alice).isCombinedForward())
    }

    @Test
    fun `isCombinedForward — single-forward bubble returns false (not CF per §4_4)`() {
        
        assertEquals(false, singleForwardMsg(alice).isCombinedForward())
    }

    @Test
    fun `isCombinedForward — multi-forward bubble returns true`() {
        assertEquals(true, cfMsg(alice, innerCount = 2).isCombinedForward())
        assertEquals(true, cfMsg(alice, innerCount = 5).isCombinedForward())
    }

    @Test
    fun `isCombinedForward — NotifyChatMessage subclass returns false`() {
        val notify = NotifyChatMessage().apply {
            this.id = "notify-1"
            this.authorId = alice
        }
        assertEquals(false, notify.isCombinedForward())
    }

    @Test
    fun `isCombinedForward — TextChatMessage with null forwardContext returns false`() {
        
        val msg = TextChatMessage().apply {
            this.id = "plain"
            this.authorId = alice
        }
        assertEquals(false, msg.isCombinedForward())
    }

    @Test
    fun `isCombinedForward — TextChatMessage with forwardContext but null forwards returns false`() {
        val msg = TextChatMessage().apply {
            this.id = "empty-ctx"
            this.authorId = alice
            this.forwardContext = ForwardContext(forwards = null, isFromGroup = false)
        }
        assertEquals(false, msg.isCombinedForward())
    }

    @Test
    fun `isCombinedForward — empty forwards list returns false`() {
        val msg = TextChatMessage().apply {
            this.id = "empty-fwds"
            this.authorId = alice
            this.forwardContext = ForwardContext(forwards = emptyList(), isFromGroup = false)
        }
        assertEquals(false, msg.isCombinedForward())
    }
    

    @Test
    fun `computeCombinedForwardMode — empty list returns UNKNOWN`() {
        assertEquals(
            CombinedForwardMode.UNKNOWN,
            NoticeAggregator.computeCombinedForwardMode(emptyList())
        )
    }

    @Test
    fun `computeCombinedForwardMode — isSubContext true overrides content, returns SUB`() {
        
        assertEquals(
            CombinedForwardMode.SUB_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardMode(
                messages = listOf(regularMsg(alice), regularMsg(bob)),
                isSubContext = true,
            )
        )
    }

    @Test
    fun `computeCombinedForwardMode — isSubContext true with empty list returns SUB`() {
        
        assertEquals(
            CombinedForwardMode.SUB_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardMode(
                messages = emptyList(),
                isSubContext = true,
            )
        )
    }

    @Test
    fun `computeCombinedForwardMode — all regular messages returns UNKNOWN`() {
        assertEquals(
            CombinedForwardMode.UNKNOWN,
            NoticeAggregator.computeCombinedForwardMode(
                listOf(regularMsg(alice), regularMsg(bob), regularMsg(carol))
            )
        )
    }

    @Test
    fun `computeCombinedForwardMode — single regular message returns UNKNOWN`() {
        assertEquals(
            CombinedForwardMode.UNKNOWN,
            NoticeAggregator.computeCombinedForwardMode(listOf(regularMsg(alice)))
        )
    }

    @Test
    fun `computeCombinedForwardMode — all CF messages returns ALL_COMBINED_FORWARD`() {
        assertEquals(
            CombinedForwardMode.ALL_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardMode(
                listOf(cfMsg(alice), cfMsg(bob), cfMsg(carol))
            )
        )
    }

    @Test
    fun `computeCombinedForwardMode — single CF message returns ALL_COMBINED_FORWARD`() {
        assertEquals(
            CombinedForwardMode.ALL_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardMode(listOf(cfMsg(alice)))
        )
    }

    @Test
    fun `computeCombinedForwardMode — mixed CF and regular returns CONTAINS_COMBINED_FORWARD`() {
        assertEquals(
            CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardMode(
                listOf(regularMsg(alice), cfMsg(bob), regularMsg(carol))
            )
        )
    }

    @Test
    fun `computeCombinedForwardMode — single-forward bubbles are treated as regular`() {
        
        assertEquals(
            CombinedForwardMode.UNKNOWN,
            NoticeAggregator.computeCombinedForwardMode(
                listOf(singleForwardMsg(alice), singleForwardMsg(bob))
            )
        )
    }

    @Test
    fun `computeCombinedForwardMode — CF plus single-forward returns CONTAINS_COMBINED_FORWARD`() {
        
        assertEquals(
            CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardMode(
                listOf(cfMsg(alice), singleForwardMsg(bob))
            )
        )
    }
    

    @Test
    fun `computeSortedSourceAuthorIds — empty list returns empty list`() {
        assertEquals(emptyList<String>(), NoticeAggregator.computeSortedSourceAuthorIds(emptyList()))
    }

    @Test
    fun `computeSortedSourceAuthorIds — single author multiple messages returns one entry`() {
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                regularMsg(alice, timeStamp = 100L),
                regularMsg(alice, timeStamp = 200L),
                regularMsg(alice, timeStamp = 300L),
            )
        )
        assertEquals(listOf(alice), result)
    }

    @Test
    fun `computeSortedSourceAuthorIds — CF sender ranks before non-CF sender (priority 1)`() {
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                regularMsg(alice, timeStamp = 100L),
                regularMsg(alice, timeStamp = 200L),
                regularMsg(alice, timeStamp = 300L),
                cfMsg(bob, timeStamp = 50L),
            )
        )
        assertEquals(listOf(bob, alice), result)
    }

    @Test
    fun `computeSortedSourceAuthorIds — neither CF, larger count ranks first (priority 2)`() {
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                regularMsg(alice, timeStamp = 100L),
                regularMsg(alice, timeStamp = 200L),
                regularMsg(alice, timeStamp = 300L),
                regularMsg(bob, timeStamp = 500L), 
            )
        )
        assertEquals(listOf(alice, bob), result)
    }

    @Test
    fun `computeSortedSourceAuthorIds — neither CF, same count, latest timestamp wins (priority 3)`() {
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                regularMsg(alice, timeStamp = 100L),
                regularMsg(bob, timeStamp = 200L),
            )
        )
        assertEquals(listOf(bob, alice), result)
    }

    @Test
    fun `computeSortedSourceAuthorIds — 3 authors mixing all 3 priority levels`() {
        
        
        
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                regularMsg(alice, timeStamp = 50L),
                regularMsg(alice, timeStamp = 150L),
                regularMsg(alice, timeStamp = 250L),
                regularMsg(bob, timeStamp = 500L),
                cfMsg(carol, timeStamp = 10L),
            )
        )
        assertEquals(listOf(carol, alice, bob), result)
    }

    @Test
    fun `computeSortedSourceAuthorIds — duplicate author across messages is deduped`() {
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                regularMsg(alice, timeStamp = 100L),
                regularMsg(alice, timeStamp = 200L),
                regularMsg(bob, timeStamp = 300L),
                regularMsg(bob, timeStamp = 400L),
                regularMsg(alice, timeStamp = 500L),
            )
        )
        assertEquals(2, result.size)
        assertTrue(result.containsAll(listOf(alice, bob)))
    }

    @Test
    fun `computeSortedSourceAuthorIds — multiple CF senders ordered by count then timestamp`() {
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                cfMsg(alice, timeStamp = 100L),
                cfMsg(bob, timeStamp = 50L),
                cfMsg(bob, timeStamp = 200L),
            )
        )
        assertEquals(listOf(bob, alice), result)
    }

    @Test
    fun `computeSortedSourceAuthorIds — full tie on priority 1-2-3 falls back to authorId ascending`() {
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                regularMsg(bob, timeStamp = 500L),
                regularMsg(alice, timeStamp = 500L),
            )
        )
        assertEquals(listOf(alice, bob), result)
    }

    @Test
    fun `computeSortedSourceAuthorIds — author group with both CF and regular still counts as CF sender`() {
        
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                cfMsg(alice, timeStamp = 100L),
                regularMsg(alice, timeStamp = 200L),
                regularMsg(bob, timeStamp = 300L),
                regularMsg(bob, timeStamp = 400L),
            )
        )
        assertEquals(listOf(alice, bob), result)
    }
    

    private fun regularTextMessage(
        authorId: String,
        timeStamp: Long = 0L,
    ): TextMessage = TextMessage(
        id = "msg-$authorId-$timeStamp",
        fromWho = For.Account(authorId),
        forWhat = For.Account("+conv"),
        systemShowTimestamp = timeStamp,
        timeStamp = timeStamp,
        receivedTimeStamp = timeStamp,
        sendType = 0,
        expiresInSeconds = 0,
        notifySequenceId = 0L,
        sequenceId = 0L,
        mode = 0,
        text = "t",
    )

    private fun cfTextMessage(
        authorId: String,
        timeStamp: Long = 0L,
        innerCount: Int = 2,
    ): TextMessage {
        require(innerCount > 1) { "CF requires > 1 inner forwards" }
        return TextMessage(
            id = "cf-$authorId-$timeStamp",
            fromWho = For.Account(authorId),
            forWhat = For.Account("+conv"),
            systemShowTimestamp = timeStamp,
            timeStamp = timeStamp,
            receivedTimeStamp = timeStamp,
            sendType = 0,
            expiresInSeconds = 0,
            notifySequenceId = 0L,
            sequenceId = 0L,
            mode = 0,
            text = "t",
        ).apply {
            forwardContext = ForwardContext(
                forwards = (0 until innerCount).map { mkForward("+inner$it") },
                isFromGroup = false,
            )
        }
    }

    @Test
    fun `TextMessage isCombinedForward — null forwardContext returns false`() {
        assertEquals(false, regularTextMessage(alice).isCombinedForward())
    }

    @Test
    fun `TextMessage isCombinedForward — multi-forward bubble returns true`() {
        assertEquals(true, cfTextMessage(alice, innerCount = 2).isCombinedForward())
        assertEquals(true, cfTextMessage(alice, innerCount = 5).isCombinedForward())
    }

    @Test
    fun `TextMessage isCombinedForward — single-forward bubble returns false`() {
        val msg = regularTextMessage(alice).apply {
            forwardContext = ForwardContext(forwards = listOf(mkForward("+inner0")), isFromGroup = false)
        }
        assertEquals(false, msg.isCombinedForward())
    }

    @Test
    fun `computeCombinedForwardModeFromTextMessages — empty list returns UNKNOWN`() {
        assertEquals(
            CombinedForwardMode.UNKNOWN,
            NoticeAggregator.computeCombinedForwardModeFromTextMessages(emptyList())
        )
    }

    @Test
    fun `computeCombinedForwardModeFromTextMessages — isSubContext true overrides content, returns SUB`() {
        assertEquals(
            CombinedForwardMode.SUB_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardModeFromTextMessages(
                messages = listOf(regularTextMessage(alice), regularTextMessage(bob)),
                isSubContext = true,
            )
        )
    }

    @Test
    fun `computeCombinedForwardModeFromTextMessages — all regular returns UNKNOWN`() {
        assertEquals(
            CombinedForwardMode.UNKNOWN,
            NoticeAggregator.computeCombinedForwardModeFromTextMessages(
                listOf(regularTextMessage(alice), regularTextMessage(bob))
            )
        )
    }

    @Test
    fun `computeCombinedForwardModeFromTextMessages — all CF returns ALL_COMBINED_FORWARD`() {
        assertEquals(
            CombinedForwardMode.ALL_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardModeFromTextMessages(
                listOf(cfTextMessage(alice), cfTextMessage(bob))
            )
        )
    }

    @Test
    fun `computeCombinedForwardModeFromTextMessages — mixed returns CONTAINS_COMBINED_FORWARD`() {
        assertEquals(
            CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardModeFromTextMessages(
                listOf(regularTextMessage(alice), cfTextMessage(bob), regularTextMessage(carol))
            )
        )
    }

    @Test
    fun `computeSortedSourceAuthorIdsFromTextMessages — empty list returns empty`() {
        assertEquals(
            emptyList<String>(),
            NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(emptyList())
        )
    }

    @Test
    fun `computeSortedSourceAuthorIdsFromTextMessages — CF sender ranks first (parity with ChatMessage path)`() {
        val result = NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(
            listOf(
                regularTextMessage(alice, timeStamp = 100L),
                regularTextMessage(alice, timeStamp = 200L),
                regularTextMessage(alice, timeStamp = 300L),
                cfTextMessage(bob, timeStamp = 50L),
            )
        )
        assertEquals(listOf(bob, alice), result)
    }

    @Test
    fun `computeSortedSourceAuthorIdsFromTextMessages — count tiebreak (parity with ChatMessage path)`() {
        val result = NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(
            listOf(
                regularTextMessage(alice, timeStamp = 100L),
                regularTextMessage(alice, timeStamp = 200L),
                regularTextMessage(alice, timeStamp = 300L),
                regularTextMessage(bob, timeStamp = 500L),
            )
        )
        assertEquals(listOf(alice, bob), result)
    }
    

    
    private fun cfTextMessageInner(
        authorId: String,
        timeStamp: Long,
        vararg innerAuthors: String,
    ): TextMessage = regularTextMessage(authorId, timeStamp).apply {
        require(innerAuthors.size > 1) { "CF requires > 1 inner forwards" }
        forwardContext = ForwardContext(forwards = innerAuthors.map { mkForward(it) }, isFromGroup = false)
    }

    @Test
    fun `computeSortedSourceAuthorIds — selfIdLast pins operator last even when it would rank first`() {
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                cfMsg(me, timeStamp = 10L),
                regularMsg(alice, timeStamp = 100L),
                regularMsg(bob, timeStamp = 200L),
            ),
            selfIdLast = me,
        )
        assertEquals(me, result.last())
        assertEquals(setOf(alice, bob), result.dropLast(1).toSet())
    }

    @Test
    fun `computeSortedSourceAuthorIds — null selfIdLast keeps legacy priority ordering`() {
        
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(cfMsg(me, timeStamp = 10L), regularMsg(alice, timeStamp = 100L)),
        )
        assertEquals(listOf(me, alice), result)
    }

    @Test
    fun `computeSortedSourceAuthorIdsFromTextMessages — selfIdLast pins operator last`() {
        val result = NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(
            listOf(
                regularTextMessage(me, timeStamp = 500L),
                regularTextMessage(alice, timeStamp = 100L),
            ),
            selfIdLast = me,
        )
        assertEquals(listOf(alice, me), result)
    }
    

    @Test
    fun `copyCarriesForeignContentFromTextMessages — all self returns false`() {
        assertEquals(
            false,
            NoticeAggregator.copyCarriesForeignContentFromTextMessages(
                listOf(regularTextMessage(me), regularTextMessage(me, 100L)), me
            )
        )
    }

    @Test
    fun `copyCarriesForeignContentFromTextMessages — contains other returns true`() {
        assertEquals(
            true,
            NoticeAggregator.copyCarriesForeignContentFromTextMessages(
                listOf(regularTextMessage(me), regularTextMessage(alice)), me
            )
        )
    }

    @Test
    fun `copyCarriesForeignContentFromTextMessages — CF copies as placeholder so no leak even with foreign inner`() {
        
        assertEquals(
            false,
            NoticeAggregator.copyCarriesForeignContentFromTextMessages(
                listOf(cfTextMessageInner(me, 0L, alice, bob)), me
            )
        )
    }

    @Test
    fun `copyCarriesForeignContentFromTextMessages — multi-select treats single-forward as placeholder (no trace)`() {
        
        
        
        val aliceFwd = regularTextMessage(alice).apply {
            forwardContext = ForwardContext(forwards = listOf(mkForward(me)), isFromGroup = false)
        }
        assertEquals(false, NoticeAggregator.copyCarriesForeignContentFromTextMessages(listOf(aliceFwd), me))
        val mineFwd = regularTextMessage(me).apply {
            forwardContext = ForwardContext(forwards = listOf(mkForward(alice)), isFromGroup = false)
        }
        assertEquals(false, NoticeAggregator.copyCarriesForeignContentFromTextMessages(listOf(mineFwd), me))
    }

    @Test
    fun `copyCarriesForeignContentFromTextMessages — mixed foreign regular plus CF placeholder returns true`() {
        assertEquals(
            true,
            NoticeAggregator.copyCarriesForeignContentFromTextMessages(
                listOf(cfTextMessageInner(me, 0L, me, me), regularTextMessage(alice)), me
            )
        )
    }

    @Test
    fun `copyCarriesForeignContent ChatMessage — single long-press mirrors getCopyableTextContent`() {
        
        assertEquals(false, NoticeAggregator.copyCarriesForeignContent(listOf(cfMsg(me)), me))
        
        assertEquals(true, NoticeAggregator.copyCarriesForeignContent(listOf(regularMsg(alice)), me))
        assertEquals(false, NoticeAggregator.copyCarriesForeignContent(listOf(regularMsg(me)), me))
    }

    @Test
    fun `copyCarriesForeignContent ChatMessage — long-press single-forward judged by original (inner) author`() {
        
        val mineInnerAlice = TextChatMessage().apply {
            id = "sf1"; authorId = me
            forwardContext = ForwardContext(forwards = listOf(mkForward(alice)), isFromGroup = false)
        }
        assertEquals(true, NoticeAggregator.copyCarriesForeignContent(listOf(mineInnerAlice), me))
        
        val aliceInnerMe = TextChatMessage().apply {
            id = "sf2"; authorId = alice
            forwardContext = ForwardContext(forwards = listOf(mkForward(me)), isFromGroup = false)
        }
        assertEquals(false, NoticeAggregator.copyCarriesForeignContent(listOf(aliceInnerMe), me))
    }
    

    @Test
    fun `forwardCarriesForeignContentFromTextMessages — all self regular returns false`() {
        assertEquals(
            false,
            NoticeAggregator.forwardCarriesForeignContentFromTextMessages(
                listOf(regularTextMessage(me), regularTextMessage(me, 100L)), me
            )
        )
    }

    @Test
    fun `forwardCarriesForeignContentFromTextMessages — foreign regular returns true`() {
        assertEquals(
            true,
            NoticeAggregator.forwardCarriesForeignContentFromTextMessages(
                listOf(regularTextMessage(alice)), me
            )
        )
    }

    @Test
    fun `forwardCarriesForeignContentFromTextMessages — my own CF with foreign inner returns true`() {
        
        assertEquals(
            true,
            NoticeAggregator.forwardCarriesForeignContentFromTextMessages(
                listOf(cfTextMessageInner(me, 0L, alice, bob)), me
            )
        )
    }

    @Test
    fun `forwardCarriesForeignContentFromTextMessages — my own CF with all-self inner returns false`() {
        assertEquals(
            false,
            NoticeAggregator.forwardCarriesForeignContentFromTextMessages(
                listOf(cfTextMessageInner(me, 0L, me, me)), me
            )
        )
    }

    @Test
    fun `forwardCarriesForeignContent ChatMessage — CF judged by inner, regular by sender`() {
        val myCfForeignInner = TextChatMessage().apply {
            id = "cf"; authorId = me
            forwardContext = ForwardContext(forwards = listOf(mkForward(alice), mkForward(bob)), isFromGroup = false)
        }
        assertEquals(true, NoticeAggregator.forwardCarriesForeignContent(listOf(myCfForeignInner), me))
        assertEquals(false, NoticeAggregator.forwardCarriesForeignContent(listOf(regularMsg(me)), me))
        assertEquals(true, NoticeAggregator.forwardCarriesForeignContent(listOf(regularMsg(alice)), me))
    }

    

    
    private fun mkContainer(author: String, vararg inner: Forward): Forward =
        Forward(0L, 0, false, author, "t", null, inner.toList(), null)

    @Test
    fun `forwardCarriesForeignContentFromTextMessages — deeply nested foreign leaf traces (recursive)`() {
        
        val msg = regularTextMessage(me).apply {
            forwardContext = ForwardContext(
                forwards = listOf(
                    mkContainer(me, mkForward(me), mkForward(me)),                       
                    mkContainer(me, mkForward(me), mkContainer(me, mkForward(alice))),   
                ),
                isFromGroup = false,
            )
        }
        assertEquals(true, NoticeAggregator.forwardCarriesForeignContentFromTextMessages(listOf(msg), me))
    }

    @Test
    fun `forwardCarriesForeignContentFromTextMessages — deeply nested all-self CH does not trace`() {
        
        val msg = regularTextMessage(me).apply {
            forwardContext = ForwardContext(
                forwards = listOf(
                    mkContainer(me, mkForward(me)),
                    mkContainer(me, mkContainer(me, mkForward(me))),
                ),
                isFromGroup = false,
            )
        }
        assertEquals(false, NoticeAggregator.forwardCarriesForeignContentFromTextMessages(listOf(msg), me))
    }

    @Test
    fun `forwardCarriesForeignContentFromTextMessages — intermediate forwarder does not trace, only original (leaf) author`() {
        
        val forwarderOnlyForeign = regularTextMessage(me).apply {
            forwardContext = ForwardContext(
                forwards = listOf(
                    mkContainer(me, mkForward(me)),
                    mkContainer(alice, mkForward(me)), 
                ),
                isFromGroup = false,
            )
        }
        assertEquals(false, NoticeAggregator.forwardCarriesForeignContentFromTextMessages(listOf(forwarderOnlyForeign), me))
        
        val foreignLeaf = regularTextMessage(me).apply {
            forwardContext = ForwardContext(
                forwards = listOf(mkContainer(me, mkForward(me)), mkContainer(me, mkForward(alice))),
                isFromGroup = false,
            )
        }
        assertEquals(true, NoticeAggregator.forwardCarriesForeignContentFromTextMessages(listOf(foreignLeaf), me))
    }

    @Test
    fun `forwardCarriesForeignContent — single-forward judged by original (inner) author`() {
        
        val mineInnerAlice = TextChatMessage().apply {
            id = "sf"; authorId = me
            forwardContext = ForwardContext(forwards = listOf(mkForward(alice)), isFromGroup = false)
        }
        assertEquals(true, NoticeAggregator.forwardCarriesForeignContent(listOf(mineInnerAlice), me))
        
        val aliceInnerMe = TextChatMessage().apply {
            id = "sf2"; authorId = alice
            forwardContext = ForwardContext(forwards = listOf(mkForward(me)), isFromGroup = false)
        }
        assertEquals(false, NoticeAggregator.forwardCarriesForeignContent(listOf(aliceInnerMe), me))
    }

    @Test
    fun `forwardCarriesForeignContentFromTextMessages — beyond max forward depth defaults to trace`() {
        
        var deepChain: Forward = mkForward(me)
        repeat(6) { deepChain = mkContainer(me, deepChain) } 
        val msg = regularTextMessage(me).apply {
            
            forwardContext = ForwardContext(forwards = listOf(deepChain, mkForward(me)), isFromGroup = false)
        }
        assertEquals(true, NoticeAggregator.forwardCarriesForeignContentFromTextMessages(listOf(msg), me))
    }
}
