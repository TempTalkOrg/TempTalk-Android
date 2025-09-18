package com.difft.android.chat.message

import com.difft.android.websocket.api.messages.TTNotifyMessage

open class NotifyChatMessage : ChatMessage() {
    var notifyMessage: TTNotifyMessage? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotifyChatMessage) return false
        if (!super.equals(other)) return false

        if (notifyMessage != other.notifyMessage) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (notifyMessage?.hashCode() ?: 0)
        return result
    }

}