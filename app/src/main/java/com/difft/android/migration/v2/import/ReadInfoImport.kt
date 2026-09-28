package com.difft.android.migration.v2.importer

import com.difft.android.migration.v2.model.NeutralConversationLocal
import org.difft.app.database.models.ReadInfoModel

/**
 * Re-insert a conversation's other-participant read positions into the `read_info` table on import —
 * the inverse of
 * [com.difft.android.migration.v2.export.NeutralProjections.participantReadPosition].
 *
 * These rows drive the outgoing-message "read by" indicator after restore: a sent message is read by
 * uid X once X's watermark reaches the message position. The migrating account's own watermark is
 * restored separately onto `RoomModel.readPosition`, so a self row — which a peer platform may send
 * — is dropped here rather than restored as a phantom "read by me" participant.
 */
internal fun WcdbWriteTarget.insertParticipantReadPositionsOf(n: NeutralConversationLocal, selfUid: String) {
    n.participantReadPositions.filterNot { it.uid == selfUid }.forEach { p ->
        insertReadInfo(ReadInfoModel().apply {
            roomId = n.conversationRef
            uid = p.uid
            readPosition = p.readPosition.maxServerTime
        })
    }
}
