package com.difft.android.chat.common

import org.difft.app.database.models.ContactorModel

object SendMessageUtils {

    private val currentChatList = LinkedHashSet<String>()
    fun addToCurrentChat(id: String) {
        currentChatList.add(id)
    }

    fun removeFromCurrentChat(id: String) {
        currentChatList.remove(id)
    }

    fun isExistChat(id: String?): Boolean {
        return currentChatList.contains(id)
    }

    /**
     * Id of the chat the user is looking at, or null when no chat is open.
     *
     * Registration order, not focus order: re-adding an already-registered id does not move it,
     * so under multi-window this may name the older of two open chats. Callers must be
     * best-effort.
     */
    fun currentChat(): String? = currentChatList.lastOrNull()
}

enum class SendType(val rawValue: Int) {
    Sending(0), Sent(1), SentFailed(2)
}
