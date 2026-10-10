package com.difft.android.chat.message

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which conversations have their chat screen showing.
 *
 * Ref-counted: a new ViewModel for the same room can be created before the previous one's
 * `onCleared` runs (dual-pane switch, quick reopen), so a plain set would drop a room that is
 * still on screen. Process-local by nature: anything that must survive process death (an UNREAD
 * sync still queued when the room is opened) is handled through the persisted Job queue instead.
 */
@Singleton
class ConversationOpenTracker @Inject constructor() {
    private val openCounts = ConcurrentHashMap<String, Int>()

    fun onOpened(roomId: String) {
        openCounts.compute(roomId) { _, count -> (count ?: 0) + 1 }
    }

    fun onClosed(roomId: String) {
        openCounts.computeIfPresent(roomId) { _, count -> if (count > 1) count - 1 else null }
    }

    fun isOpen(roomId: String): Boolean = openCounts.containsKey(roomId)
}
