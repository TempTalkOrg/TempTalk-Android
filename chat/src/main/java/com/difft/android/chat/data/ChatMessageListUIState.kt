package com.difft.android.chat.data

import com.difft.android.chat.message.ChatMessage

data class ChatMessageListUIState(
    val chatMessages: List<ChatMessage>,
    val scrollToPosition: Int,
    val stateTriggeredByUser: Boolean = true //means if the state is triggered by user action
){
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChatMessageListUIState

        if (chatMessages != other.chatMessages) return false
        if (scrollToPosition != other.scrollToPosition) return false
        if (stateTriggeredByUser != other.stateTriggeredByUser) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chatMessages.hashCode()
        result = 31 * result + scrollToPosition
        result = 31 * result + stateTriggeredByUser.hashCode()
        return result
    }
}