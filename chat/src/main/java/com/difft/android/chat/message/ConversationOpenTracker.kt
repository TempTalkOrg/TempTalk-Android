package com.difft.android.chat.message

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton









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
