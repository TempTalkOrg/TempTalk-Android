package com.difft.android.chat.translate

import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.TranslateData













internal fun applyTranslateOverlay(messages: List<ChatMessage>, overlay: Map<String, TranslateData>) {
    if (overlay.isEmpty()) return
    messages.forEach { message ->
        if (message is TextChatMessage) {
            overlay[message.id]?.let { message.translateData = it }
        }
    }
}
