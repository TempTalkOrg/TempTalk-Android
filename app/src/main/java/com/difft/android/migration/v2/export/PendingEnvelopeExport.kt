package com.difft.android.migration.v2.export

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.messages.PendingMessageProcessor
import com.difft.android.migration.v2.container.PendingEnvelopeBytes
import com.difft.android.migration.v2.model.NeutralPendingEnvelope
import com.difft.android.migration.v2.model.NeutralRecord
import org.difft.app.database.models.PendingMessageModelNew







object PendingEnvelopeExport {

    
    fun select(rows: List<PendingMessageModelNew>, nowMs: Long): List<NeutralRecord.PendingEnvelope> {
        var expired = 0
        var invalid = 0
        val carried = rows.mapNotNull { row ->
            val messageId = row.messageId
            when {
                PendingMessageProcessor.isExpired(row.originalMessageTimeStamp, nowMs) -> {
                    expired++
                    null
                }
                messageId.isNullOrBlank() || row.originalMessageTimeStamp <= 0 ||
                    row.messageEnvelopBytes.isEmpty() || !PendingMessageProcessor.isParseable(row.messageEnvelopBytes) -> {
                    invalid++
                    null
                }
                else -> NeutralRecord.PendingEnvelope(
                    NeutralPendingEnvelope(
                        messageId = messageId,
                        originalMessageTimeStamp = row.originalMessageTimeStamp,
                        messageEnvelopBytes = PendingEnvelopeBytes.encode(row.messageEnvelopBytes),
                    ),
                )
            }
        }
        L.i { "[MigrationV2][Export] pending envelopes rows=${rows.size} carried=${carried.size} expired=$expired invalid=$invalid" }
        return carried
    }
}
