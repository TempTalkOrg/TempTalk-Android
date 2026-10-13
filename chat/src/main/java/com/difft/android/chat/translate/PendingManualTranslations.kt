package com.difft.android.chat.translate

import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.TranslateTargetLanguage
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual translations that ended in `FailedModel` because a pack was not ready within the wait,
 * kept so they can be re-run once every pack they need — target and detected source — turns ready
 * while the page is still open.
 */
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

    /** Removes and returns every entry whose models all [isReady]; a conditional remove hands each out once. */
    fun takeReady(isReady: (ModelId) -> Boolean): List<Entry> =
        entries.entries.mapNotNull { (id, entry) ->
            entry.takeIf { it.models.all(isReady) && entries.remove(id, it) }
        }
}
