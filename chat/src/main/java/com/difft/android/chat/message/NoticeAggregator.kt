package com.difft.android.chat.message

import difft.android.messageserialization.model.CombinedForwardMode
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
     * Receiver displays the first 5 names and a locale-specific overflow suffix.
     */
    fun computeSortedSourceAuthorIds(messages: List<ChatMessage>): List<String> {
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
            .sortedWith(
                // PRD §5.3.4 priorities (1→2→3) + deterministic authorId tiebreaker so the
                // wire order is stable across upstream call sites when all three priorities tie.
                compareByDescending<AuthorAggregate> { it.isCombinedForwardSender }
                    .thenByDescending { it.contributedCount }
                    .thenByDescending { it.latestTimestamp }
                    .thenBy { it.authorId }
            )
            .map { it.authorId }
    }

    private data class AuthorAggregate(
        val authorId: String,
        val isCombinedForwardSender: Boolean,
        val contributedCount: Int,
        val latestTimestamp: Long,
    )

    // ----- TextMessage overloads -----
    // Multi-select dispatch sites (ChatMessageViewModel.onCopyClick / onCombineClick /
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
    fun computeSortedSourceAuthorIdsFromTextMessages(messages: List<TextMessage>): List<String> {
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
            .sortedWith(
                // PRD §5.3.4 priorities (1→2→3) + deterministic authorId tiebreaker so the
                // wire order is stable across upstream call sites when all three priorities tie.
                compareByDescending<AuthorAggregate> { it.isCombinedForwardSender }
                    .thenByDescending { it.contributedCount }
                    .thenByDescending { it.latestTimestamp }
                    .thenBy { it.authorId }
            )
            .map { it.authorId }
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
