package com.difft.android.chat.mention

import com.difft.android.chat.contacts.contactsall.PinyinSortKey
import com.difft.android.chat.contacts.contactsall.toPinyinSortKey
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import org.difft.app.database.models.ContactorModel

/**
 * Aggregated stats of how often the current user mentioned a member in the current chat
 * within the rolling 14-day window. Only present for uids mentioned inside that window.
 */
internal data class MentionStats(
    val lastMentionTime: Long,        // max(systemShowTimestamp) over messages mentioning this uid (my own sent messages: ≈ send time)
    val lastMentionMessageId: String, // id of that message (log/trace only; not part of comparison, see design §5)
    val count24h: Int,                // de-duplicated messages mentioning this uid within now-24h
    val count14d: Int,                // de-duplicated messages mentioning this uid within now-14d (= total mentions)
)

/** Snapshot of sort data for one chat, produced by a single DB load. now/counts share one instant. */
internal data class MentionStatsSnapshot(
    val roomId: String,
    val now: Long,
    val mentionStats: Map<String, MentionStats>, // key = uid (excludes MENTIONS_ALL_ID)
    val lastSpeakTime: Map<String, Long>,        // key = uid, value = latest speak systemShowTimestamp within 14d
)

/** Pure candidate row for the sorter, decoupled from ContactorModel for testability. */
internal data class MentionCandidate(
    val uid: String,
    val displayName: String,          // getDisplayNameForUI()
    val displayNameNoRemark: String,  // getDisplayNameWithoutRemarkForUI()
    val base58Id: String,             // id.formatBase58Id() → "TT-xxxx"
    val pinyinKey: PinyinSortKey,     // precomputed from displayName, fallback ordering
) {
    companion object {
        fun from(c: ContactorModel): MentionCandidate {
            val name = c.getDisplayNameForUI()
            return MentionCandidate(
                uid = c.id,
                displayName = name,
                displayNameNoRemark = c.getDisplayNameWithoutRemarkForUI(),
                base58Id = c.id.formatBase58Id(),
                pinyinKey = name.toPinyinSortKey(),
            )
        }
    }
}
