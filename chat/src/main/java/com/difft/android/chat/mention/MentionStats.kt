package com.difft.android.chat.mention

import com.difft.android.chat.contacts.contactsall.PinyinSortKey
import com.difft.android.chat.contacts.contactsall.toPinyinSortKey
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import org.difft.app.database.models.ContactorModel





internal data class MentionStats(
    val lastMentionTime: Long,        
    val lastMentionMessageId: String, 
    val count24h: Int,                
    val count14d: Int,                
)


internal data class MentionStatsSnapshot(
    val roomId: String,
    val now: Long,
    val mentionStats: Map<String, MentionStats>, 
    val lastSpeakTime: Map<String, Long>,        
)


internal data class MentionCandidate(
    val uid: String,
    val displayName: String,          
    val displayNameNoRemark: String,  
    val base58Id: String,             
    val pinyinKey: PinyinSortKey,     
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
