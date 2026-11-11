package com.difft.android.migration.v2.importer

import android.util.Base64
import difft.android.messageserialization.model.ScreenShot
import difft.android.messageserialization.model.RealSource
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.common.SendType
import com.difft.android.migration.v2.PerConversationTally
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.container.PendingEnvelopeBytes
import com.difft.android.migration.v2.export.NeutralProjections
import com.difft.android.migration.v2.model.AttachmentOwner
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralForward
import com.difft.android.migration.v2.model.NeutralCredential
import com.difft.android.migration.v2.model.NeutralIdentity
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralPendingEnvelope
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.model.SendState
import com.difft.android.migration.v2.readtime.ReadLadder
import com.difft.android.migration.v2.restore.AttachmentFileNaming
import org.difft.app.database.GENERIC_CONTENT_TYPE
import org.difft.app.database.NO_SERVER_ATTACHMENT_ID
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.PendingMessageModelNew
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.RoomModel
import java.util.UUID























class NeutralImporter(
    private val target: WcdbWriteTarget,
    




    private val messageAttachmentDir: (ownerKey: String) -> String = FileUtil::getMessageAttachmentFilePath,
    




    private val selfUid: String = "",
    



    private val previewStrings: RoomStateRecomputer.PreviewStrings = RoomStateRecomputer.PreviewStrings.ResUtilsBacked,
    





    private val identitySink: ((NeutralIdentity) -> Unit)? = null,
    




    private val credentialSink: ((NeutralCredential) -> Unit)? = null,
    



    private val onImported: (Long) -> Unit = {},
) {

    
    private val pending = ArrayList<() -> Unit>(BATCH_SIZE)
    private var imported = 0

    
    private val gson = Gson()

    



    private val DEFAULT_DEVICE_ID = 1

    





    private val forwardPkByIdentity = HashMap<OwnerKey, Long>()

    private data class OwnerKey(val author: String, val sentAt: Long)

    




    private val placementByContentRef = HashMap<String, AttachmentPlacement>()

    







    data class AttachmentPlacement(val ownerKey: String, val fileName: String) {
        
        val relativePath: String get() = "$ownerKey/$fileName"
    }

    




    fun placementFor(contentRef: String): AttachmentPlacement? = placementByContentRef[contentRef]

    




    private val importedMsgByConversation = HashMap<String, Int>()

    
    private val batchConvRefs = ArrayList<String>()
    private val roomsUpserted = HashSet<String>()

    





    private val skippedMessageIds = HashSet<String>()
    private val skippedContentRefs = HashSet<String>()
    private var skippedUnknownKind = 0
    private var skippedUnknownOwner = 0
    private var skippedCascadeAttachments = 0
    private var recordFailures = 0
    private var pendingEnvelopes = 0

    




    private val bytesAbsentRefs = LinkedHashSet<String>()
    private var bytesAbsent = 0

    
    fun isSkippedBytes(contentRef: String): Boolean = contentRef in skippedContentRefs

    





    fun onAttachmentBytesAbsent(contentRef: String) {
        if (contentRef in skippedContentRefs) return
        bytesAbsentRefs.add(contentRef)
    }

    







    fun onRecord(record: NeutralRecord) {
        if (record is NeutralRecord.PendingEnvelope) {
            importPendingEnvelope(record.value)
            return
        }
        try {
            when (record) {
                is NeutralRecord.Message -> {
                    
                    
                    if (record.value.contentKind == ContentKind.UNKNOWN) {
                        skippedMessageIds.add(record.value.stableId)
                        skippedUnknownKind++
                        return
                    }
                    enqueueMessage(record.value)
                }
                is NeutralRecord.Attachment -> {
                    
                    if (record.value.messageRef in skippedMessageIds) {
                        skippedContentRefs.add(record.value.stableId)
                        skippedCascadeAttachments++
                        return
                    }
                    
                    
                    @Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
                    if ((record.value.owner as AttachmentOwner?) == null) {
                        skippedContentRefs.add(record.value.stableId)
                        skippedUnknownOwner++
                        L.w { "[MigrationV2][Import] attachment id=${record.value.stableId} has an unknown owner — skipped" }
                        return
                    }
                    enqueueAttachment(record.value)
                }
                is NeutralRecord.ConversationLocal -> enqueueConversation(record.value)
                is NeutralRecord.GroupKey -> enqueue { target.insertGroupKey(groupKey(record.value)) }
                
                
                is NeutralRecord.Identity -> identitySink?.invoke(record.value)
                
                
                is NeutralRecord.Credential -> credentialSink?.invoke(record.value)
                
                is NeutralRecord.PendingEnvelope -> Unit
            }
        } catch (e: Exception) {
            recordFailures++
            L.w { "[MigrationV2][Import] record skipped after mapping failure: ${e.stackTraceToString()}" }
        }
    }

    








    private fun importPendingEnvelope(value: NeutralPendingEnvelope) {
        try {
            val model = PendingMessageModelNew().apply {
                messageId = value.messageId
                originalMessageTimeStamp = value.originalMessageTimeStamp
                messageEnvelopBytes = PendingEnvelopeBytes.decode(value.messageEnvelopBytes)
            }
            target.runInTransaction { target.insertPendingEnvelope(model) }
            pendingEnvelopes++
        } catch (e: Exception) {
            L.e { "[MigrationV2][Import] event=pending_envelope_write_failed ${e.stackTraceToString()}" }
            throw MigrationException.RestoreFailed("pending envelope write failed", e)
        }
    }

    
    fun finish() {
        flush()
        applyBytesAbsent()
        applyReadLadders()
        
        
        try {
            RoomStateRecomputer.recompute(target, selfUid, previewStrings)
        } catch (e: Exception) {
            L.e { "[MigrationV2][Import] room-state recompute failed, rows kept: ${e.stackTraceToString()}" }
        }
        L.i {
            "[MigrationV2][Import] done imported=$imported skippedUnknownKind=$skippedUnknownKind " +
                "skippedUnknownOwner=$skippedUnknownOwner " +
                "skippedCascadeAttachments=$skippedCascadeAttachments bytesAbsent=$bytesAbsent " +
                "recordFailures=$recordFailures pendingEnvelopes=$pendingEnvelopes"
        }
        
        
        L.i {
            "[MigrationV2][Import] per-conversation roomsUpserted=${roomsUpserted.size} " +
                "msgByConversation=${PerConversationTally.format(importedMsgByConversation)}"
        }
    }

    




    private fun applyBytesAbsent() {
        bytesAbsentRefs.forEach { ref ->
            try {
                target.markAttachmentBytesAbsent(ref)
                bytesAbsent++
            } catch (e: Exception) {
                L.w { "[MigrationV2][Import] attachment id=$ref bytes-absent mark failed: ${e.stackTraceToString()}" }
            }
        }
    }

    



    private val laddersByRoom = LinkedHashMap<String, ReadLadder>()

    



    private var exceptionsApplied = 0

    










    private fun applyReadLadders() {
        var rooms = 0
        var rows = 0
        var failures = 0
        laddersByRoom.forEach { (roomId, ladder) ->
            try {
                ladder.rows.sortedBy { it.readAt }.forEach { row ->
                    target.markReadUpTo(roomId, row.maxServerTime, row.readAt, selfUid)
                    rows++
                }
                val watermark = ladder.watermark
                val watermarkReadAt = ladder.effectiveWatermarkReadAt()
                if (watermark != null && watermarkReadAt != null) {
                    target.markReadUpTo(roomId, watermark.maxServerTime, watermarkReadAt, selfUid)
                }
                rooms++
            } catch (e: Exception) {
                failures++
                L.w { "[MigrationV2][Import] read ladder skipped for one room: ${e.stackTraceToString()}" }
            }
        }
        L.i {
            "[MigrationV2][Import] read ladders applied rooms=$rooms rows=$rows " +
                "exceptionsApplied=$exceptionsApplied failures=$failures"
        }
    }

    

    private fun enqueue(op: () -> Unit) {
        pending.add(op)
        if (pending.size >= BATCH_SIZE) flush()
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        try {
            target.runInTransaction {
                batch.forEach { it() }
            }
            imported += batch.size
            commitTallies()
            onImported(imported.toLong())
        } catch (e: Exception) {
            
            
            L.w { "[MigrationV2][Import] batch of ${batch.size} rolled back, replaying singly: ${e.message}" }
            batchConvRefs.clear()
            batch.forEach { op ->
                try {
                    target.runInTransaction(op)
                    imported++
                    commitTallies()
                } catch (single: Exception) {
                    recordFailures++
                    batchConvRefs.clear()
                    L.w { "[MigrationV2][Import] record skipped after write failure: ${single.stackTraceToString()}" }
                }
            }
            onImported(imported.toLong())
        }
    }

    
    private fun commitTallies() {
        batchConvRefs.forEach { importedMsgByConversation[it] = (importedMsgByConversation[it] ?: 0) + 1 }
        batchConvRefs.clear()
    }

    

    private fun enqueueMessage(n: NeutralMessage) {
        
        
        
        if (n.expireStartedAt != null) exceptionsApplied++
        enqueueMessageWrite(n)
    }

    private fun enqueueMessageWrite(n: NeutralMessage) = enqueue {
        
        val quotePk: Long? = n.quote?.let { q ->
            val pk = target.insertQuote(QuoteModel().apply {
                id = q.sentAt          
                author = q.author
                text = q.textPreview ?: ""
            })
            
            
            
            q.attachmentsMeta.forEach { meta ->
                target.insertAttachment(AttachmentModel().apply {
                    id = meta.stableId
                    quoteModelDatabaseId = pk
                    contentType = meta.contentType
                    fileName = meta.fileName ?: ""
                })
            }
            pk
        }

        
        
        
        
        val forwardContextPk: Long? = n.forwardContext?.let { fc ->
            val fcPk = target.insertForwardContext(ForwardContextModel().apply { isFromGroup = fc.isFromGroup })
            var rows = 0
            fc.roots.forEach { root ->
                rows += insertForwardTree(root, forwardContextPk = fcPk, parentPk = null)
            }
            
            
            L.i { "[MigrationV2][Import] forward msg=${n.stableId} fcPk=$fcPk roots=${fc.roots.size} rows=$rows" }
            fcPk
        }

        
        target.insertMessage(MessageModel().apply {
            id = n.stableId
            roomId = n.conversationRef
            
            roomType = n.roomType
            fromWho = n.sender
            
            
            
            messageText = if (n.contentKind == ContentKind.SYSTEM) systemMessageText(n) else n.body
            timeStamp = n.sentAt
            systemShowTimestamp = n.serverAt
            receivedTimeStamp = n.receivedAt ?: 0L
            expiresInSeconds = n.expireTimer
            type = messageType(n)
            
            
            
            sendType = if (n.direction == MessageDirection.OUTGOING && n.sendState == SendState.FAILED) {
                SendType.SentFailed.rawValue
            } else {
                SendType.Sent.rawValue
            }
            mode = if (n.confidential) 1 else 0
            playStatus = if (n.platformExtras[EXTRA_PLAYED] == "false") PLAY_STATUS_NOT_PLAYED else 0
            
            
            
            
            readTime = when {
                n.expireStartedAt == null -> if (n.direction == MessageDirection.OUTGOING) n.serverAt else 0L
                n.expireStartedAt > 0L -> n.expireStartedAt
                else -> READ_TIME_NOT_STARTED
            }
            sequenceId = n.sequenceId ?: 0L
            notifySequenceId = n.notifySequenceId ?: 0L
            criticalAlertType = n.criticalAlertType ?: 0
            quoteDatabaseId = quotePk
            forwardContextDatabaseId = forwardContextPk
            
            
            
            atPersons = n.mentions.takeIf { it.isNotEmpty() }?.joinToString(";") { it.uid }
            
            
            receiverIds = n.recipients.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
            screenShotJson = n.screenShot?.let {
                
                
                gson.toJson(ScreenShot(RealSource(it.author, DEFAULT_DEVICE_ID, it.sentAt, n.serverAt)))
            }
        })
        
        batchConvRefs.add(n.conversationRef)

        
        n.mentions.forEach { mn ->
            target.insertMention(MentionModel().apply {
                messageId = n.stableId
                forwardModelDatabaseId = null
                start = mn.start
                length = mn.length
                uid = mn.uid
                
                
                type = mn.type ?: MENTION_TYPE_INTERNAL
            })
        }
        
        
        n.reactions.filterNot { it.removed }.forEach { r ->
            target.insertReaction(ReactionModel().apply {
                messageId = n.stableId
                emoji = r.emoji
                uid = r.fromUid
                timeStamp = r.sentAt
            })
        }

        
        target.insertSharedContactsOf(n)
    }

    






    private fun systemMessageText(n: NeutralMessage): String? {
        n.systemPayload?.takeIf { it.isNotEmpty() }?.let { return it }
        val displayText = n.displayText ?: return n.body
        val json = JsonObject().apply {
            addProperty("showContent", displayText)
            n.systemType?.toIntOrNull()?.let { addProperty("notifyType", it) }
        }
        return gson.toJson(json)
    }

    




    private fun insertForwardTree(node: NeutralForward, forwardContextPk: Long, parentPk: Long?): Int {
        val myPk = target.insertForward(ForwardModel().apply {
            id = node.sentAt
            author = node.author
            text = node.body ?: ""
            serverTimestamp = node.sentAt
            forwardContextDatabaseId = forwardContextPk
            parentForwardModelDatabaseId = parentPk
        })
        
        
        forwardPkByIdentity[OwnerKey(node.author, node.sentAt)] = myPk
        var count = 1
        node.nested.forEach { child ->
            count += insertForwardTree(child, forwardContextPk = forwardContextPk, parentPk = myPk)
        }
        return count
    }

    private fun enqueueAttachment(n: NeutralAttachment) {
        
        
        
        
        
        
        val localId = UUID.randomUUID().toString()
        
        
        val rowFileName = attachmentFileName(n, localId)
        if (!n.pointerOnly) {
            placementByContentRef[n.stableId] = AttachmentPlacement(localId, rowFileName)
        }

        enqueue {
            
            
            
            var fwdFk: Long? = null
            var msgRef: String? = null
            when (n.owner) {
                AttachmentOwner.MESSAGE -> msgRef = n.messageRef
                AttachmentOwner.FORWARD -> {
                    fwdFk = ownerPk(forwardPkByIdentity, n)
                    if (fwdFk == null) L.w {
                        "[MigrationV2][Import] forward attachment id=${n.stableId} owner not found author=${n.ownerAuthor} sentAt=${n.ownerSentAt}"
                    }
                }
            }
            target.insertAttachment(AttachmentModel().apply {
                id = n.stableId
                messageId = msgRef
                forwardModelDatabaseId = fwdFk
                
                
                contentType = n.contentType ?: GENERIC_CONTENT_TYPE
                size = n.size.toInt()
                width = n.width ?: 0
                height = n.height ?: 0
                totalTime = n.durationMs
                flags = n.flags
                amplitudes = n.amplitudes
                fileName = rowFileName
                
                
                
                authorityId = n.serverAttachmentId ?: NO_SERVER_ATTACHMENT_ID
                n.encryptionKey?.let { key = Base64.decode(it, Base64.NO_WRAP) }
                
                n.digestBase64?.let { digest = Base64.decode(it, Base64.NO_WRAP) }
                
                
                
                
                val fetchable = (n.serverAttachmentId ?: NO_SERVER_ATTACHMENT_ID) != NO_SERVER_ATTACHMENT_ID &&
                    !n.encryptionKey.isNullOrEmpty()
                status = when {
                    !n.pointerOnly -> ATTACHMENT_STATUS_SUCCESS
                    !fetchable -> ATTACHMENT_STATUS_EXPIRED
                    else -> 0
                }
                
                
                this.localId = localId
            })
        }
    }

    











    private fun attachmentFileName(n: NeutralAttachment, localId: String): String {
        val carried = n.fileName?.trim().orEmpty()
        if (carried.isNotEmpty() && FileUtil.isFileNameValid(carried) && AttachmentFileNaming.isUsable(carried)) {
            return carried
        }
        val fallback = listOfNotNull(n.serverAttachmentId?.toString(), n.stableId)
            .firstOrNull { it.isNotBlank() && AttachmentFileNaming.isUsable(it) }
            ?: localId
        L.i { "[MigrationV2][Import] attachment id=${n.stableId} fileName absent or unusable — stored as $fallback" }
        return fallback
    }

    




    private fun ownerPk(map: Map<OwnerKey, Long>, n: NeutralAttachment): Long? {
        val sentAt = n.ownerSentAt ?: return null
        return map[OwnerKey(n.ownerAuthor.orEmpty(), sentAt)]
    }

    private fun enqueueConversation(n: com.difft.android.migration.v2.model.NeutralConversationLocal) {
        
        
        laddersByRoom[n.conversationRef] = ReadLadder.fromWire(n)
        enqueue {
            target.upsertRoom(RoomModel().apply {
                roomId = n.conversationRef
                
                
                roomType = n.roomType
                pinnedTime = n.pinnedTime
                readPosition = n.readPosition?.maxServerTime ?: 0L
            })
            roomsUpserted.add(n.conversationRef)
            
            target.insertParticipantReadPositionsOf(n, selfUid)
        }
    }

    private fun groupKey(n: com.difft.android.migration.v2.model.NeutralGroupKey) =
        GroupCryptoKeysModel().apply {
            gid = n.groupId
            rGroup = n.rGroup
            keyVersion = n.keyVersion
        }

    

    private fun messageType(n: NeutralMessage): Int = when (n.contentKind) {
        ContentKind.SYSTEM -> MessageModel.TYPE_NOTIFY
        ContentKind.FILE, ContentKind.IMAGE, ContentKind.VIDEO, ContentKind.VOICE -> MessageModel.TYPE_ATTACHMENT
        else -> MessageModel.TYPE_TEXT
    }

    companion object {
        
        const val BATCH_SIZE = 500

        
        private const val MENTION_TYPE_INTERNAL = 0

        
        private const val ATTACHMENT_STATUS_SUCCESS = 3
        private const val ATTACHMENT_STATUS_EXPIRED = 5

        





        private const val READ_TIME_NOT_STARTED = -1L

        
        private const val PLAY_STATUS_NOT_PLAYED = 1

        
        private const val EXTRA_PLAYED = "android.played"
    }
}
