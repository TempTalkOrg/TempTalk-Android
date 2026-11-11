package com.difft.android.migration.v2.verify

import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralGroupKey
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord














object RoundTripVerifier {

    
    data class FieldMismatch(
        val entity: String,
        val stableId: String,
        val field: String,
        val source: Any?,
        val imported: Any?,
    ) {
        override fun toString(): String =
            "[$entity:$stableId] $field: source=$source imported=$imported"
    }

    
    data class Result(
        val pass: Boolean,
        val mismatches: List<FieldMismatch>,
        val missing: List<String>,   
        val extra: List<String>,     
    ) {
        fun report(): String = buildString {
            appendLine(if (pass) "ROUND-TRIP PASS" else "ROUND-TRIP FAIL")
            if (missing.isNotEmpty()) appendLine("Missing (${missing.size}): $missing")
            if (extra.isNotEmpty()) appendLine("Extra (${extra.size}): $extra")
            mismatches.forEach { appendLine("  $it") }
        }
    }

    





    fun verify(source: List<NeutralRecord>, imported: List<NeutralRecord>): Result {
        val srcMap = source.associateBy { keyOf(it) }
        val impMap = imported.associateBy { keyOf(it) }

        val mismatches = ArrayList<FieldMismatch>()
        val missing = srcMap.keys.filter { it !in impMap }.sorted()
        val extra = impMap.keys.filter { it !in srcMap }.sorted()

        for ((key, src) in srcMap) {
            val imp = impMap[key] ?: continue
            mismatches += diff(src, imp)
        }

        return Result(
            pass = mismatches.isEmpty() && missing.isEmpty() && extra.isEmpty(),
            mismatches = mismatches,
            missing = missing,
            extra = extra,
        )
    }

    private fun keyOf(r: NeutralRecord): String = when (r) {
        is NeutralRecord.Message -> "message:${r.value.stableId}"
        is NeutralRecord.Attachment -> "attachment:${r.value.stableId}"
        is NeutralRecord.ConversationLocal -> "conversation:${r.value.conversationRef}"
        is NeutralRecord.GroupKey -> "groupKey:${r.value.groupId}"
        
        
        
        is NeutralRecord.Identity -> "identity"
        
        
        is NeutralRecord.Credential -> "credential"
        is NeutralRecord.PendingEnvelope -> "pendingEnvelope:${r.value.messageId}"
    }

    private fun diff(src: NeutralRecord, imp: NeutralRecord): List<FieldMismatch> = when {
        src is NeutralRecord.Message && imp is NeutralRecord.Message -> diffMessage(src.value, imp.value)
        src is NeutralRecord.Attachment && imp is NeutralRecord.Attachment -> diffAttachment(src.value, imp.value)
        src is NeutralRecord.ConversationLocal && imp is NeutralRecord.ConversationLocal -> diffConversation(src.value, imp.value)
        src is NeutralRecord.GroupKey && imp is NeutralRecord.GroupKey -> diffGroupKey(src.value, imp.value)
        else -> listOf(FieldMismatch("record", keyOf(src), "variant", src::class.simpleName, imp::class.simpleName))
    }

    private fun diffMessage(s: NeutralMessage, i: NeutralMessage): List<FieldMismatch> {
        val id = s.stableId
        val m = ArrayList<FieldMismatch>()
        fun cmp(field: String, a: Any?, b: Any?) {
            if (a != b) m += FieldMismatch("message", id, field, a, b)
        }
        cmp("conversationRef", s.conversationRef, i.conversationRef)
        cmp("roomType", s.roomType, i.roomType)
        cmp("sender", s.sender, i.sender)
        cmp("direction", s.direction, i.direction)
        cmp("body", s.body, i.body)
        cmp("sentAt", s.sentAt, i.sentAt)
        cmp("serverAt", s.serverAt, i.serverAt)
        cmp("receivedAt", s.receivedAt, i.receivedAt)
        cmp("expireTimer", s.expireTimer, i.expireTimer)
        cmp("expireStartedAt", s.expireStartedAt, i.expireStartedAt)
        cmp("contentKind", s.contentKind, i.contentKind)
        cmp("systemType", s.systemType, i.systemType)
        cmp("displayText", s.displayText, i.displayText)
        cmp("confidential", s.confidential, i.confidential)
        cmp("systemPayload", s.systemPayload, i.systemPayload)
        cmp("sendState", s.sendState, i.sendState)
        cmp("sequenceId", s.sequenceId, i.sequenceId)
        cmp("notifySequenceId", s.notifySequenceId, i.notifySequenceId)
        cmp("criticalAlertType", s.criticalAlertType, i.criticalAlertType)
        cmp("quote", s.quote, i.quote)
        cmp("forwardContext", s.forwardContext, i.forwardContext)
        
        cmp("mentions", s.mentions.toSet(), i.mentions.toSet())
        cmp("reactions", s.reactions.toSet(), i.reactions.toSet())
        
        
        
        cmp("sharedContacts", s.sharedContacts.toSet(), i.sharedContacts.toSet())
        cmp("attachmentRefs", s.attachmentRefs.toSet(), i.attachmentRefs.toSet())
        cmp("platformExtras", s.platformExtras, i.platformExtras)
        return m
    }

    private fun diffAttachment(s: NeutralAttachment, i: NeutralAttachment): List<FieldMismatch> {
        val id = s.stableId
        val m = ArrayList<FieldMismatch>()
        fun cmp(field: String, a: Any?, b: Any?) {
            if (a != b) m += FieldMismatch("attachment", id, field, a, b)
        }
        cmp("messageRef", s.messageRef, i.messageRef)
        cmp("contentType", s.contentType, i.contentType)
        cmp("size", s.size, i.size)
        cmp("width", s.width, i.width)
        cmp("height", s.height, i.height)
        cmp("durationMs", s.durationMs, i.durationMs)
        cmp("flags", s.flags, i.flags)
        cmp("amplitudes", s.amplitudes, i.amplitudes)
        cmp("fileName", s.fileName, i.fileName)
        cmp("serverAttachmentId", s.serverAttachmentId, i.serverAttachmentId)
        cmp("encryptionKey", s.encryptionKey, i.encryptionKey)
        cmp("pointerOnly", s.pointerOnly, i.pointerOnly)
        
        cmp("owner", s.owner, i.owner)
        cmp("ownerAuthor", s.ownerAuthor, i.ownerAuthor)
        cmp("ownerSentAt", s.ownerSentAt, i.ownerSentAt)
        return m
    }

    private fun diffConversation(s: NeutralConversationLocal, i: NeutralConversationLocal): List<FieldMismatch> {
        val id = s.conversationRef
        val m = ArrayList<FieldMismatch>()
        
        if (s.roomType != i.roomType) m += FieldMismatch("conversation", id, "roomType", s.roomType, i.roomType)
        if (s.pinnedTime != i.pinnedTime) m += FieldMismatch("conversation", id, "pinnedTime", s.pinnedTime, i.pinnedTime)
        if (s.readPosition != i.readPosition) m += FieldMismatch("conversation", id, "readPosition", s.readPosition, i.readPosition)
        
        
        if (s.participantReadPositions.toSet() != i.participantReadPositions.toSet()) {
            m += FieldMismatch("conversation", id, "participantReadPositions", s.participantReadPositions.toSet(), i.participantReadPositions.toSet())
        }
        return m
    }

    private fun diffGroupKey(s: NeutralGroupKey, i: NeutralGroupKey): List<FieldMismatch> =
        if (s.rGroup != i.rGroup) listOf(FieldMismatch("groupKey", s.groupId, "rGroup", s.rGroup, i.rGroup)) else emptyList()
}
