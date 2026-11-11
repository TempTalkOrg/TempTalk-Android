package com.difft.android.chat.translate

import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.TranslateTargetLanguage
import java.util.concurrent.ConcurrentHashMap






internal class PendingManualTranslations {

    data class Entry(val message: TextChatMessage, val targetLanguage: TranslateTargetLanguage, val models: Set<ModelId>)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun record(message: TextChatMessage, targetLanguage: TranslateTargetLanguage, models: Set<ModelId>) {
        entries[message.id] = Entry(message, targetLanguage, models)
    }

    fun remove(messageId: String) {
        entries.remove(messageId)
    }

    fun clear() = entries.clear()

    
    fun takeReady(isReady: (ModelId) -> Boolean): List<Entry> =
        entries.entries.mapNotNull { (id, entry) ->
            entry.takeIf { it.models.all(isReady) && entries.remove(id, it) }
        }
}
