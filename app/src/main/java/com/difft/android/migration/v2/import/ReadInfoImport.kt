package com.difft.android.migration.v2.importer

import com.difft.android.migration.v2.model.NeutralConversationLocal
import org.difft.app.database.models.ReadInfoModel











internal fun WcdbWriteTarget.insertParticipantReadPositionsOf(n: NeutralConversationLocal, selfUid: String) {
    n.participantReadPositions.filterNot { it.uid == selfUid }.forEach { p ->
        insertReadInfo(ReadInfoModel().apply {
            roomId = n.conversationRef
            uid = p.uid
            readPosition = p.readPosition.maxServerTime
        })
    }
}
