package difft.android.messageserialization.model

import java.io.Serializable

/**
 * Immutable translation state of a single message. Every field is read-only on purpose: callers
 * replace the whole value with [copy] instead of mutating a shared instance, so equality-based
 * list diffing can still tell an old state from a new one.
 */
data class TranslateData(
    val translateStatus: TranslateStatus,
    val translatedContentCN: String?,
    val translatedContentEN: String?
) : Serializable

/**
 * Persistable states are exactly [Invisible], [ShowCN] and [ShowEN]. The remaining values are
 * transient session state, live only in memory and are rejected by the message store.
 */
enum class TranslateStatus(val status: Int) {
    Invisible(0),

    /** Transient, never persisted. */
    Translating(1),
    ShowCN(2),
    ShowEN(3),

    /** Source text is already in the target language. Transient, never persisted. */
    SameLanguage(4),

    /** Translation failed and can be retried. Transient, never persisted. */
    Failed(5),

    /** Source text exceeds the engine length limit. Transient, never persisted. */
    FailedTooLong(6);

    /**
     * Whether this state may be written to the `translate` table. Transient states are session
     * state only: persisting them would resurrect a spinner or an error row on the next load.
     */
    val isPersistable: Boolean
        get() = this == Invisible || this == ShowCN || this == ShowEN

    companion object {
        fun fromIntOrDefault(status: Int): TranslateStatus {
            return entries.find { it.status == status } ?: Invisible
        }
    }
}

enum class TranslateTargetLanguage(val language: String) {
    ZH("zh-cn"),
    EN("en")
}

enum class TranslateMsgType(val type: Int) {
    DTTranslateMsgSourceUnknown(0),
    DTTranslateMsgSource1On1(1),
    DTTranslateMsgSourceGroup(2),
    DTTranslateMsgSourceAnnouncement(3),
    DTTranslateMsgSourceNormalBot(4)
}
