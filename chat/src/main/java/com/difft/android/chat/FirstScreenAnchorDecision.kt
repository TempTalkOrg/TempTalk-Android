package com.difft.android.chat

/**
 * First-screen anchoring decision for the normal (non-jump) chat entry.
 *
 * Pure by construction: the whole decision table lives here so it can be unit-tested without
 * WCDB / Android.
 */
internal sealed interface FirstScreenAnchor {
    /** Window built around readPosition, divider rule untouched. */
    data object FromReadPosition : FirstScreenAnchor

    /** Move the first-screen window so it starts at the earliest failed outgoing message. */
    data object AtFailedMessage : FirstScreenAnchor
}

/**
 * Decide whether the first screen should be moved onto the earliest failed outgoing message.
 *
 * @param firstFailedTs `systemShowTimestamp` of the earliest failed outgoing message, or null when
 *   the room has none. Archive tombstones are already excluded by the query's type predicate — see
 *   `WCDB.earliestFailedOutgoingMessage`; do NOT re-check the sentinel timestamp here, a second
 *   spelling of that rule is how the two drift apart.
 * @param firstUnreadOthersTs `systemShowTimestamp` of the first message that is BOTH newer than
 *   readPosition AND not mine — i.e. exactly the message the "NEW MESSAGES" divider lands on
 *   (`ChatMessageViewModel`). MUST come from `WCDB.firstUnreadFromOthersMessage`, never from
 *   `ChatNormalPaginationController`'s `expectedUnreadMessages.firstOrNull()`: that set has no
 *   `fromWho` predicate (`WcdbChatMessageWindowSource.roomCondition` filters on room only), so a
 *   failed message — necessarily mine and usually newer than readPosition — would be its first
 *   element and make the decision fire `FromReadPosition` unconditionally, silently disabling the
 *   anchoring entirely.
 */
internal fun decideFirstScreenAnchor(
    firstFailedTs: Long?,
    firstUnreadOthersTs: Long?,
): FirstScreenAnchor {
    if (firstFailedTs == null) return FirstScreenAnchor.FromReadPosition
    // Anchor at the failure exactly when it is the EARLIER of the two: FromReadPosition already
    // opens on the first unread (the default window queries `systemShowTimestamp > readPosition`
    // ascending), so the whole rule is "open at the earliest thing the user has not dealt with".
    //
    // No distance test on purpose. An earlier revision refused to anchor when the failure sat more
    // than a page from the divider target, to keep the divider on the first screen — but the
    // divider is now a session-scoped anchor held by ChatMessageViewModel, so it no longer has to
    // be on the first screen to survive; it renders whenever its boundary is in the loaded window.
    // And landing far back is exactly what the unread jump has always done.
    return if (firstUnreadOthersTs == null || firstFailedTs < firstUnreadOthersTs) {
        FirstScreenAnchor.AtFailedMessage
    } else {
        FirstScreenAnchor.FromReadPosition
    }
}
