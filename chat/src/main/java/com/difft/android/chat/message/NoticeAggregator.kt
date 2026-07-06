package com.difft.android.chat.message

import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.TextMessage

/**
 * Pure helpers that derive the wire-protocol fields for copy/forward notices
 * from a list of operated-on messages, per PRD v1.0 §5.3.
 *
 * No UI / DB / network dependencies — safe to unit-test offline.
 */
object NoticeAggregator {

    /**
     * Decide the [CombinedForwardMode] for a selection.
     *
     *  @param messages       Bubbles operated on at the main-conversation level.
     *                        For SUB context, pass the list of inner sub-messages.
     *  @param isSubContext   true iff the operation is inside a CF detail view
     *                        (any nesting level), per PRD §5.3.2 "详情视图展开".
     *                        When true, the selection-composition branch is skipped
     *                        and SUB_COMBINED_FORWARD is returned unconditionally.
     */
    fun computeCombinedForwardMode(
        messages: List<ChatMessage>,
        isSubContext: Boolean = false,
    ): CombinedForwardMode {
        if (isSubContext) return CombinedForwardMode.SUB_COMBINED_FORWARD
        if (messages.isEmpty()) return CombinedForwardMode.UNKNOWN

        val cfCount = messages.count { it.isCombinedForward() }
        return when {
            cfCount == 0 -> CombinedForwardMode.UNKNOWN
            cfCount == messages.size -> CombinedForwardMode.ALL_COMBINED_FORWARD
            else -> CombinedForwardMode.CONTAINS_COMBINED_FORWARD
        }
    }

    /**
     * Build the deduped, sorted author-id list for the notice's `sourceAuthorIds`
     * field, per PRD §5.3.4 priority ranking:
     *   1) CF (combined-forward) bubble senders first
     *   2) Authors contributing more selected messages first (desc)
     *   3) Authors with the most-recent selected message first (desc by timestamp)
     *
     * PRD v2.0 §改动3: when [selfIdLast] is supplied (the operator's own id), the
     * operator is pinned to the END of the list regardless of the priorities above,
     * so a mixed (self + others) selection always reads "...others, You" last. Pure
     * self-only / pure others selections are unaffected (self-only no longer leaves a
     * trace at all; others-only has no self to move).
     *
     * Receiver displays the first 5 names and a locale-specific overflow suffix.
     */
    fun computeSortedSourceAuthorIds(messages: List<ChatMessage>, selfIdLast: String? = null): List<String> {
        if (messages.isEmpty()) return emptyList()
        return messages
            .groupBy { it.authorId }
            .map { (authorId, group) ->
                AuthorAggregate(
                    authorId = authorId,
                    isCombinedForwardSender = group.any { it.isCombinedForward() },
                    contributedCount = group.size,
                    latestTimestamp = group.maxOf { it.timeStamp },
                )
            }
            .sortedWith(selfLastComparator(selfIdLast))
            .map { it.authorId }
    }

    private data class AuthorAggregate(
        val authorId: String,
        val isCombinedForwardSender: Boolean,
        val contributedCount: Int,
        val latestTimestamp: Long,
    )

    // ----- TextMessage overloads -----
    // Multi-select dispatch sites (ChatMessageViewModel.onCopyClick / onForwardClick /
    // onSaveSelectedMessages, ChatMessageInputFragment single-forward) hold
    // List<TextMessage> from `convertToTextMessage()` rather than List<ChatMessage>.
    // Avoid forcing callers to wrap into ChatMessage just to feed the aggregator.

    /** [TextMessage] variant of [computeCombinedForwardMode]. */
    fun computeCombinedForwardModeFromTextMessages(
        messages: List<TextMessage>,
        isSubContext: Boolean = false,
    ): CombinedForwardMode {
        if (isSubContext) return CombinedForwardMode.SUB_COMBINED_FORWARD
        if (messages.isEmpty()) return CombinedForwardMode.UNKNOWN

        val cfCount = messages.count { it.isCombinedForward() }
        return when {
            cfCount == 0 -> CombinedForwardMode.UNKNOWN
            cfCount == messages.size -> CombinedForwardMode.ALL_COMBINED_FORWARD
            else -> CombinedForwardMode.CONTAINS_COMBINED_FORWARD
        }
    }

    /** [TextMessage] variant of [computeSortedSourceAuthorIds]. */
    fun computeSortedSourceAuthorIdsFromTextMessages(messages: List<TextMessage>, selfIdLast: String? = null): List<String> {
        if (messages.isEmpty()) return emptyList()
        return messages
            .groupBy { it.fromWho.id }
            .map { (authorId, group) ->
                AuthorAggregate(
                    authorId = authorId,
                    isCombinedForwardSender = group.any { it.isCombinedForward() },
                    contributedCount = group.size,
                    latestTimestamp = group.maxOf { it.timeStamp },
                )
            }
            .sortedWith(selfLastComparator(selfIdLast))
            .map { it.authorId }
    }

    /**
     * PRD §5.3.4 priorities (1→2→3) + deterministic authorId tiebreaker for a stable
     * wire order; PRD v2.0 §改动3 pins [selfIdLast] (the operator) dead last when present.
     */
    private fun selfLastComparator(selfIdLast: String?): Comparator<AuthorAggregate> =
        // false < true ⇒ the operator (authorId == selfIdLast) always sorts after everyone else.
        compareBy<AuthorAggregate> { selfIdLast != null && it.authorId == selfIdLast }
            .thenByDescending { it.isCombinedForwardSender }
            .thenByDescending { it.contributedCount }
            .thenByDescending { it.latestTimestamp }
            .thenBy { it.authorId }

    // ----- PRD v2.0 §改动1/§改动2: "leave a trace" gating (ORIGINAL-author based) -----
    // Core principle: trace ONLY when another person's ORIGINAL content leaves the conversation.
    // Three message classes (§改动2), all judged by the ORIGINAL author (原作者), never the forwarder:
    //   - 原生 message (no forwardContext — plain/image/file/contact): the bubble author IS the
    //     original author.
    //   - single-forward (forwards.size == 1): the forwarded content's original author (recurse to
    //     the leaf); the forwarder/bubble only feeds the `from` display, never the trigger.
    //   - Chat History (forwards.size > 1): recurse all layers to the leaf (original) authors.
    // Forwarders at intermediate layers do NOT count — only leaf (original) messages do.

    /** Recursion cap (§改动2 fallback). Beyond this depth — or on a malformed tree — default to trace. */
    private const val MAX_FORWARD_DEPTH = 4

    /**
     * COPY gating for MULTI-SELECT (List<TextMessage>). On Android/Mac multi-select copy renders ANY
     * forward bubble — single-forward OR combined-forward — as a `[Chat History]` placeholder (see
     * [com.difft.android.chat.message.MessageCopyTextFormatter]); no real content leaves, so forward
     * bubbles never count. Only a non-forward 原生 message (plain/image/file/contact) by someone else
     * counts. (The PRD's "single-forward copy traces by original author" assumes iOS, which copies the
     * inner text; Android's placeholder clipboard means there is nothing real to trace on multi-select.)
     */
    fun copyCarriesForeignContentFromTextMessages(messages: List<TextMessage>, myId: String): Boolean =
        messages.any { it.forwardContext == null && it.fromWho.id != myId }

    /**
     * COPY gating for SINGLE long-press / derived (one [TextChatMessage]). Here the clipboard gets the
     * REAL text (a single-forward copies its inner text via
     * [com.difft.android.chat.message.getCopyableTextContent]; derived translate/STT copies the shown
     * text), so it is judged by the ORIGINAL author — single-forward by the inner (original) author;
     * a combined-forward copies nothing and never counts.
     */
    fun copyCarriesForeignContent(messages: List<ChatMessage>, myId: String): Boolean =
        messages.any { !it.isCombinedForward() && carriesForeignOriginalAuthor((it as? TextChatMessage)?.forwardContext, it.authorId, myId) }

    /**
     * FORWARD gating. Judged by the ORIGINAL author for every type — 原生 by its sender, single-forward
     * and Chat History recurse to the leaf (original) authors. Unlike copy, a Chat History is NOT a
     * placeholder here (its inner content actually leaves on forward).
     */
    fun forwardCarriesForeignContentFromTextMessages(messages: List<TextMessage>, myId: String): Boolean =
        messages.any { carriesForeignOriginalAuthor(it.forwardContext, it.fromWho.id, myId) }

    /** [ChatMessage] variant of [forwardCarriesForeignContentFromTextMessages]. */
    fun forwardCarriesForeignContent(messages: List<ChatMessage>, myId: String): Boolean =
        messages.any { carriesForeignOriginalAuthor((it as? TextChatMessage)?.forwardContext, it.authorId, myId) }

    /**
     * True iff this message carries another person's ORIGINAL content (the trace trigger).
     * 原生 (no forwardContext): the bubble author is the original author. single-forward / CH:
     * recurse to the leaf (original) messages, skipping every forwarder.
     */
    private fun carriesForeignOriginalAuthor(forwardContext: ForwardContext?, bubbleAuthorId: String, myId: String): Boolean {
        val forwards = forwardContext?.forwards
        if (forwards.isNullOrEmpty()) return bubbleAuthorId != myId
        return leafAuthorsContainForeign(forwards, myId, depth = 1)
    }

    /**
     * Recurse to the leaf (original) authors of a forward tree; any leaf authored by someone other
     * than [myId] → foreign. §改动2 fallback: beyond [MAX_FORWARD_DEPTH] default to foreign (true).
     */
    private fun leafAuthorsContainForeign(forwards: List<Forward>, myId: String, depth: Int): Boolean {
        if (depth > MAX_FORWARD_DEPTH) return true
        return forwards.any { fwd ->
            val nested = fwd.forwards
            if (nested.isNullOrEmpty()) fwd.author != myId
            else leafAuthorsContainForeign(nested, myId, depth + 1)
        }
    }
}

/**
 * Mirrors [ChatMessage.isCombinedForward]'s rule (PRD v1.0 §4.4) for the serialization-layer
 * [TextMessage]: a bubble is "combined-forward" iff its [TextMessage.forwardContext] carries
 * more than one top-level forward. A `forwards.size == 1` bubble is a single forwarded
 * message rendered in regular UI — NOT a CF.
 */
fun TextMessage.isCombinedForward(): Boolean {
    val forwards = forwardContext?.forwards ?: return false
    return forwards.size > 1
}
