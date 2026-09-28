package com.difft.android.chat.translate

import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.TranslateData

/**
 * Makes the coordinator's overlay authoritative over the values hydration read from the database,
 * for the lifetime of the conversation page.
 *
 * A translation write is fire-and-forget, so a window re-query already in flight when it happens
 * hands back the pre-write value and would blink the translate block away. Messages absent from
 * [overlay] keep whatever hydration gave them.
 *
 * Pure and mutation-based on purpose: every [ChatMessage] here was freshly built by
 * `generateMessageTwo` for this assembly pass, and [TranslateData] is immutable, so assigning a new
 * value can never be seen by the previous list — which is what lets DiffUtil detect the change.
 */
internal fun applyTranslateOverlay(messages: List<ChatMessage>, overlay: Map<String, TranslateData>) {
    if (overlay.isEmpty()) return
    messages.forEach { message ->
        if (message is TextChatMessage) {
            overlay[message.id]?.let { message.translateData = it }
        }
    }
}
