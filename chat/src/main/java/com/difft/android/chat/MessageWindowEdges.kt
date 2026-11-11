package com.difft.android.chat

import com.difft.android.chat.pagination.ChatMessageWindowSource
import org.difft.app.database.models.MessageModel


internal data class WindowEdges(
    val anchorBefore: MessageModel?,
    val anchorAfter: MessageModel?,
)













internal fun ChatMessageWindowSource.resolveWindowEdges(oldestTs: Long, newestTs: Long) = WindowEdges(
    anchorBefore = olderThan(oldestTs, 1L).firstOrNull(),
    anchorAfter = newerThan(newestTs, 1L).firstOrNull(),
)


internal data class LatestWindow(
    val droppedNeighbour: MessageModel?,
    val pageMessages: List<MessageModel>,
)









internal fun takeLatestWindow(sortedAsc: List<MessageModel>, max: Int): LatestWindow =
    if (sortedAsc.size > max) {
        LatestWindow(
            droppedNeighbour = sortedAsc[sortedAsc.size - max - 1],
            pageMessages = sortedAsc.takeLast(max),
        )
    } else {
        LatestWindow(droppedNeighbour = null, pageMessages = sortedAsc)
    }
