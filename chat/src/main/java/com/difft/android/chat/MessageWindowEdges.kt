package com.difft.android.chat

import com.difft.android.chat.pagination.ChatMessageWindowSource
import org.difft.app.database.models.MessageModel

/** The two invisible layout anchors surrounding a loaded window. */
internal data class WindowEdges(
    val anchorBefore: MessageModel?,
    val anchorAfter: MessageModel?,
)

/**
 * Resolves the anchors for a window whose displayed rows span `[oldestTs, newestTs]`.
 *
 * Two LIMIT-1 index probes on the same `(roomId, roomType, systemShowTimestamp)` path pagination
 * already uses, expressed through [ChatMessageWindowSource] so this stays winq-free and runs on the
 * host JVM.
 *
 * Distinct from `splitMessageWindow`, which derives anchors from an over-fetched candidate list: a
 * null anchor THERE can mean "we did not look", while a null anchor HERE means "no such row
 * exists". That is what makes `anchorAfter == null` usable as the `hasReachedLatest` signal on the
 * observer path.
 */
internal fun ChatMessageWindowSource.resolveWindowEdges(oldestTs: Long, newestTs: Long) = WindowEdges(
    anchorBefore = olderThan(oldestTs, 1L).firstOrNull(),
    anchorAfter = newerThan(newestTs, 1L).firstOrNull(),
)

/** A window capped at the newest end, plus the row that fell off its oldest end (if any). */
internal data class LatestWindow(
    val droppedNeighbour: MessageModel?,
    val pageMessages: List<MessageModel>,
)

/**
 * Keeps the newest [max] of [sortedAsc] (ascending by `systemShowTimestamp`) and reports the row
 * immediately older than the kept slice as [LatestWindow.droppedNeighbour] — that row becomes the
 * before-anchor, since it is provably adjacent to the window's new oldest row.
 *
 * Single owner of the truncation `loadNextPage` used to carry inline, now shared with
 * `trimToLatest`. When nothing is dropped the input list is returned BY REFERENCE, so a caller can
 * tell "no truncation happened" without comparing contents.
 */
internal fun takeLatestWindow(sortedAsc: List<MessageModel>, max: Int): LatestWindow =
    if (sortedAsc.size > max) {
        LatestWindow(
            droppedNeighbour = sortedAsc[sortedAsc.size - max - 1],
            pageMessages = sortedAsc.takeLast(max),
        )
    } else {
        LatestWindow(droppedNeighbour = null, pageMessages = sortedAsc)
    }
