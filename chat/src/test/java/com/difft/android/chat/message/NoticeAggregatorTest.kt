package com.difft.android.chat.message

import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.TextMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NoticeAggregator] — pure data-layer helper, no Robolectric / MockK needed.
 *
 * Test fixtures use minimal `TextChatMessage` / `NotifyChatMessage` instances directly;
 * the helper only reads `authorId`, `timeStamp`, and (for `isCombinedForward`)
 * `forwardContext.forwards.size`.
 */
class NoticeAggregatorTest {

    private val alice = "+10001"
    private val bob = "+10002"
    private val carol = "+10003"

    // ---------- Test fixture builders ----------

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

    /** Regular (non-CF) text message. */
    private fun regularMsg(
        authorId: String,
        timeStamp: Long = 0L,
    ): TextChatMessage = TextChatMessage().apply {
        this.id = "msg-$authorId-$timeStamp"
        this.authorId = authorId
        this.timeStamp = timeStamp
    }

    /** Combined-forward (chat history) text message: forwards.size > 1. */
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

    /** Text message that has a single forward (looks forwarded but is NOT CF per §4.4). */
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

    // ================================================================
    // isCombinedForward() — extension sanity checks
    // ================================================================

    @Test
    fun `isCombinedForward — regular text message returns false`() {
        assertEquals(false, regularMsg(alice).isCombinedForward())
    }

    @Test
    fun `isCombinedForward — single-forward bubble returns false (not CF per §4_4)`() {
        // forwards.size == 1 is just a single forwarded message rendered in regular UI.
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
        // Default for a plain TextChatMessage — forwardContext is null.
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

    // ================================================================
    // computeCombinedForwardMode — branches
    // ================================================================

    @Test
    fun `computeCombinedForwardMode — empty list returns UNKNOWN`() {
        assertEquals(
            CombinedForwardMode.UNKNOWN,
            NoticeAggregator.computeCombinedForwardMode(emptyList())
        )
    }

    @Test
    fun `computeCombinedForwardMode — isSubContext true overrides content, returns SUB`() {
        // Pass non-empty regular messages; isSubContext should still win.
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
        // PRD §5.3.2: SUB context is determined by the caller's surface, not the content.
        // Empty + sub returns SUB (caller convention; never actually empty in practice).
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
        // A bubble whose forwardContext.forwards.size == 1 is NOT a CF per §4.4.
        assertEquals(
            CombinedForwardMode.UNKNOWN,
            NoticeAggregator.computeCombinedForwardMode(
                listOf(singleForwardMsg(alice), singleForwardMsg(bob))
            )
        )
    }

    @Test
    fun `computeCombinedForwardMode — CF plus single-forward returns CONTAINS_COMBINED_FORWARD`() {
        // The single-forward bubble doesn't count as CF, so mix → CONTAINS.
        assertEquals(
            CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
            NoticeAggregator.computeCombinedForwardMode(
                listOf(cfMsg(alice), singleForwardMsg(bob))
            )
        )
    }

    // ================================================================
    // computeSortedSourceAuthorIds — branches
    // ================================================================

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
        // Alice sent more messages but Bob is the CF sender → Bob wins by priority 1.
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
        // Same timestamp range; Alice contributes 3, Bob contributes 1 → Alice first.
        val result = NoticeAggregator.computeSortedSourceAuthorIds(
            listOf(
                regularMsg(alice, timeStamp = 100L),
                regularMsg(alice, timeStamp = 200L),
                regularMsg(alice, timeStamp = 300L),
                regularMsg(bob, timeStamp = 500L), // most recent but lowest count
            )
        )
        assertEquals(listOf(alice, bob), result)
    }

    @Test
    fun `computeSortedSourceAuthorIds — neither CF, same count, latest timestamp wins (priority 3)`() {
        // Both contribute 1 each; Bob's timestamp is newer → Bob first.
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
        // Priority resolution:
        //   1. CF sender first → carol (CF), even though her count is 1
        //   2. Then higher count → alice (count=3) over bob (count=1)
        //   3. (priority 3 doesn't trigger here since p1+p2 already disambiguate)
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
        // 5 messages, 2 distinct authors → 2-entry result.
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
        // All CF senders; tie on priority 1 → priority 2 (count) → bob (2) > alice (1).
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
        // Both authors NOT CF, both contribute 1 message, both same timestamp →
        // tiebreaker is authorId lexical ascending. "+10001" < "+10002" → alice first.
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
        // Alice sent 1 CF + 1 regular; Bob sent 2 regular. Alice ranks first by priority 1
        // (she's a CF sender; any CF in her group elevates her).
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

    // ================================================================
    // TextMessage overloads — Phase 4 dispatch sites consume List<TextMessage>
    // (from `convertToTextMessage()`); these tests pin parity with the ChatMessage path.
    // ================================================================

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
}
