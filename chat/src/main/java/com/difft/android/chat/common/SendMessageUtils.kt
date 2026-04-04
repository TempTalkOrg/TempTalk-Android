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
}

enum class SendType(val rawValue: Int) {
    Sending(0), Sent(1), SentFailed(2)
}
