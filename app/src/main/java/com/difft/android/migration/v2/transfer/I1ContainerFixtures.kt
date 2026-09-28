package com.difft.android.migration.v2.transfer

import com.difft.android.migration.v2.container.AttachmentByteSource
import com.difft.android.migration.v2.container.AttachmentBytes
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord
import difft.android.messageserialization.model.AttachmentStatus
import java.io.ByteArrayInputStream

/**
 * Synthesized container payload used to exercise the transfer byte pipe without reading the real
 * database: N fake neutral records plus one in-memory attachment byte source. Shared by the debug
 * demo and the fusion test so both drive the identical payload.
 */
object I1ContainerFixtures {

    const val CONVERSATION_REF = "room-i1"
    const val ATTACHMENT_REF = "att-i1"

    /** Deterministic bytes, so the receiver can byte-compare what it got. */
    fun attachmentBytes(sizeBytes: Int = 7000): ByteArray =
        ByteArray(sizeBytes) { (it * 29 + 11).toByte() }

    /**
     * [recordCount] fake records (minimum 2): one conversation, one attachment record whose bytes
     * ride as a separate binary frame, and the rest text messages.
     */
    fun records(recordCount: Int = 5): List<NeutralRecord> {
        val n = recordCount.coerceAtLeast(2)
        val out = ArrayList<NeutralRecord>(n)
        out += NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = CONVERSATION_REF))
        out += attachmentRecord()
        repeat(n - 2) { i -> out += message("m-$i", "fake message $i") }
        return out
    }

    /** Yields [attachmentBytes] for [ATTACHMENT_REF], null otherwise. */
    fun attachmentSource(sizeBytes: Int = 7000): AttachmentByteSource = AttachmentByteSource { ref ->
        if (ref == ATTACHMENT_REF) {
            val bytes = attachmentBytes(sizeBytes)
            AttachmentBytes(bytes.size.toLong(), ByteArrayInputStream(bytes))
        } else {
            null
        }
    }

    private fun attachmentRecord(sizeBytes: Int = 7000): NeutralRecord.Attachment = NeutralRecord.Attachment(
        NeutralAttachment(
            stableId = ATTACHMENT_REF, messageRef = "m-img", contentType = "image/jpeg",
            size = sizeBytes.toLong(), width = 320, height = 240, durationMs = null,
            flags = 0, fileName = "photo.jpg",
            serverAttachmentId = 12345L, encryptionKey = "a2V5",
            digestBase64 = "ZGln",
        )
    )

    private fun message(id: String, body: String): NeutralRecord.Message = NeutralRecord.Message(
        NeutralMessage(
            stableId = id, conversationRef = CONVERSATION_REF, sender = "u-self",
            direction = MessageDirection.INCOMING, body = body, sentAt = 1000,
            serverAt = 1010, receivedAt = 1020, expireTimer = 0,
            contentKind = ContentKind.TEXT, systemType = null, confidential = false,
            attachmentRefs = emptyList(),
            expireStartedAt = 1030,
        )
    )
}
