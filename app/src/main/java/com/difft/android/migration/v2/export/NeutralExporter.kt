package com.difft.android.migration.v2.export

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.base.utils.FileUtil
import com.difft.android.migration.v2.PerConversationTally
import com.difft.android.migration.v2.container.AttachmentByteSource
import com.difft.android.migration.v2.container.AttachmentBytes
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.readtime.ReadLadder
import com.difft.android.migration.v2.readtime.ReadLadderBuilder
import com.tencent.wcdb.winq.Order
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBReadInfoModel
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.migration.v2.container.RecordJsonCodec
import com.difft.android.migration.v2.container.TransferProgress
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take


















interface NeutralExporter {

    



    fun export(): Flow<NeutralRecord>

    




    fun attachmentByteSource(): AttachmentByteSource

    
    suspend fun estimateCarriedAttachmentBytes(): Long

    




    suspend fun preCount(): ContainerTotals

    companion object {
        
        const val PAGE_SIZE = 500L
    }
}






private data class BytePlan(val encryptPath: String)

@Singleton
class NeutralExporterImpl @Inject constructor(
    private val readSource: MigrationReadSource,
) : NeutralExporter {

    






    var selfUid: String = ""

    







    var includeAttachments: Boolean = true

    




    internal var attachmentDir: (String) -> String = FileUtil::getMessageAttachmentFilePath

    
    
    private val refToPlan = HashMap<String, BytePlan>()

    override fun export(): Flow<NeutralRecord> = flow {
        L.i { "[MigrationV2][Export] start" }
        refToPlan.clear()

        
        var groupKeyCount = 0
        readSource.groupCryptoKeys.allObjects.forEach { g ->
            currentCoroutineContext().ensureActive()
            emit(NeutralRecord.GroupKey(NeutralProjections.groupKey(g)))
            groupKeyCount++
        }

        
        var conversationCount = 0
        
        
        val conversationRefSet = HashSet<String>()
        
        
        
        
        
        val skippedEmptyRooms = HashSet<String>()
        val rooms = readSource.room.allObjects
        rooms.forEach { r ->
            
            
            if (r.roomId != selfUid && r.lastDisplayContent.isNullOrEmpty()) skippedEmptyRooms.add(r.roomId)
        }
        val roomReadPositions = rooms.associate { it.roomId to it.readPosition }
        
        
        val ladders = buildReadLadders(skippedEmptyRooms, roomReadPositions)
        rooms.forEach { r ->
            currentCoroutineContext().ensureActive()
            if (r.roomId in skippedEmptyRooms) return@forEach
            
            
            val readInfoRows = readSource.readInfo.getAllObjects(DBReadInfoModel.roomId.eq(r.roomId))
            emit(
                NeutralRecord.ConversationLocal(
                    NeutralProjections.conversationLocal(r, readInfoRows, ladders[r.roomId] ?: ReadLadder.EMPTY, selfUid)
                )
            )
            conversationCount++
            conversationRefSet.add(r.roomId)
        }

        
        var messageCount = 0
        var attachmentCount = 0
        var pointerOnlyCount = 0
        
        
        var readTimeExceptions = 0
        
        
        val messagesByConversation = HashMap<String, Int>()
        var cursor = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readSource.message.getAllObjects(
                DBMessageModel.databaseId.gt(cursor),
                DBMessageModel.databaseId.order(Order.Asc),
                NeutralExporter.PAGE_SIZE,
            )
            if (page.isEmpty()) break
            val sub = SubStructurePage.load(readSource, page)
            for (m in page) {
                currentCoroutineContext().ensureActive()
                
                
                if (m.roomId in skippedEmptyRooms) continue
                val roomId = m.roomId ?: ""
                val neutral = NeutralProjections.message(
                    readSource,
                    m,
                    selfUid,
                    ladders[roomId] ?: ReadLadder.EMPTY,
                    roomReadPositions[roomId] ?: 0L,
                )
                if (neutral.expireStartedAt != null) readTimeExceptions++
                emit(NeutralRecord.Message(neutral))
                messageCount++
                val convRef = m.roomId ?: ""
                messagesByConversation[convRef] = (messagesByConversation[convRef] ?: 0) + 1
                
                
                
                for (am in sub.attachmentsOf(m)) {
                    val neutral = projectNormalAttachment(am, m.id)
                    if (neutral.pointerOnly) pointerOnlyCount++
                    emit(NeutralRecord.Attachment(neutral))
                    attachmentCount++
                }
                
                
                
                
                
                for (oa in sub.ownedAttachmentsOf(m)) {
                    val neutral = projectOwnedAttachment(oa)
                    if (neutral.pointerOnly) pointerOnlyCount++
                    emit(NeutralRecord.Attachment(neutral))
                    attachmentCount++
                }
            }
            
            cursor = page.last().databaseId.toLong()
            if (page.size < NeutralExporter.PAGE_SIZE) break
        }

        L.i {
            "[MigrationV2][Export] done groupKeys=$groupKeyCount " +
                "conversations=$conversationCount messages=$messageCount attachments=$attachmentCount " +
                "pointerOnly=$pointerOnlyCount (bytes-carried=${attachmentCount - pointerOnlyCount}) " +
                "skippedEmptyRooms=${skippedEmptyRooms.size}"
        }
        L.i {
            "[MigrationV2][Export] read ladders rooms=${ladders.size} " +
                "rows=${ladders.values.sumOf { it.rows.size }} exceptions=$readTimeExceptions"
        }
        
        
        L.i {
            "[MigrationV2][Export] per-conversation rooms=${conversationRefSet.size} " +
                "msgCounts=${PerConversationTally.format(messagesByConversation)}"
        }
    }

    







    private suspend fun buildReadLadders(
        skippedEmptyRooms: Set<String>,
        roomReadPositions: Map<String, Long>,
    ): Map<String, ReadLadder> {
        val readsByRoom = HashMap<String, MutableList<ReadLadderBuilder.IncomingRead>>()
        var cursor = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readSource.message.getAllObjects(
                DBMessageModel.databaseId.gt(cursor),
                DBMessageModel.databaseId.order(Order.Asc),
                NeutralExporter.PAGE_SIZE,
            )
            if (page.isEmpty()) break
            for (m in page) {
                val roomId = m.roomId ?: continue
                if (roomId in skippedEmptyRooms) continue
                
                if (NeutralProjections.isOutgoing(m, selfUid)) continue
                if (m.readTime <= 0L) continue
                readsByRoom.getOrPut(roomId) { ArrayList() }
                    .add(ReadLadderBuilder.IncomingRead(NeutralProjections.serverAt(m), m.readTime))
            }
            cursor = page.last().databaseId.toLong()
            if (page.size < NeutralExporter.PAGE_SIZE) break
        }
        return roomReadPositions.filterKeys { it !in skippedEmptyRooms }
            .mapValues { (roomId, readPosition) -> ReadLadderBuilder.build(readsByRoom[roomId].orEmpty(), readPosition) }
    }

    override suspend fun estimateCarriedAttachmentBytes(): Long = preCount().attachmentBytes

    override suspend fun preCount(): ContainerTotals = walk().let {
        ContainerTotals(recordCount = it.records, attachmentCount = it.carried, attachmentBytes = it.carriedBytes)
    }

    





    suspend fun estimate(): ExportEstimate {
        val w = walk()
        val perRecord = sampleMessageBytes() ?: TransferProgress.RECORD_WEIGHT_BYTES
        return ExportEstimate(
            recordCount = w.records,
            recordBytes = w.records * perRecord,
            attachmentBytes = w.carriedBytes,
            voiceNoteBytes = w.voiceNoteBytes,
        )
    }

    
    private suspend fun sampleMessageBytes(): Long? {
        val codec = RecordJsonCodec()
        var sampled = 0
        var bytes = 0L
        runCatching {
            export().filter { it is NeutralRecord.Message }.take(ESTIMATE_SAMPLE_MESSAGES).collect { record ->
                sampled++
                bytes += codec.encodeRecord(record).toByteArray(Charsets.UTF_8).size
            }
        }.onFailure { e ->
            L.w { "[MigrationV2][Export] record sampling failed, using the nominal weight: ${e.message}" }
        }
        return if (sampled == 0) null else bytes / sampled
    }

    private class Walk(val records: Int, val carried: Int, val carriedBytes: Long, val voiceNoteBytes: Long)

    private suspend fun walk(): Walk {
        
        
        var records = readSource.groupCryptoKeys.allObjects.size
        val skippedEmptyRooms = HashSet<String>()
        readSource.room.allObjects.forEach { r ->
            if (r.roomId != selfUid && r.lastDisplayContent.isNullOrEmpty()) skippedEmptyRooms.add(r.roomId) else records++
        }
        var carried = 0
        var carriedBytes = 0L
        var voiceNoteBytes = 0L
        var cursor = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readSource.message.getAllObjects(
                DBMessageModel.databaseId.gt(cursor),
                DBMessageModel.databaseId.order(Order.Asc),
                NeutralExporter.PAGE_SIZE,
            )
            if (page.isEmpty()) break
            val sub = SubStructurePage.load(readSource, page)
            for (m in page) {
                currentCoroutineContext().ensureActive()
                if (m.roomId in skippedEmptyRooms) continue
                records++
                
                sub.attachmentsOf(m).forEach { am ->
                    records++
                    val len = planFileLength(resolveBytePlan(am, legacyOwnerKey = m.id))
                    if (len > 0L) {
                        carried++
                        carriedBytes += len
                        if (isVoiceNote(am)) voiceNoteBytes += len
                    }
                }
                
                sub.ownedAttachmentsOf(m).forEach { oa ->
                    records++
                    val len = planFileLength(
                        resolveBytePlan(oa.model, legacyOwnerKey = oa.model.authorityId?.toString()),
                    )
                    if (len > 0L) {
                        carried++
                        carriedBytes += len
                    }
                }
            }
            cursor = page.last().databaseId.toLong()
            if (page.size < NeutralExporter.PAGE_SIZE) break
        }
        L.i {
            "[MigrationV2][Export] preCount records=$records carriedAttachments=$carried " +
                "carriedBytes=$carriedBytes voiceNoteBytes=$voiceNoteBytes includeAttachments=$includeAttachments " +
                "skippedEmptyRooms=${skippedEmptyRooms.size}"
        }
        return Walk(records, carried, carriedBytes, voiceNoteBytes)
    }

    
    private fun planFileLength(plan: BytePlan?): Long =
        plan?.let { File(it.encryptPath).takeIf { f -> f.exists() }?.length() } ?: 0L

    
    
    
    
    

    




    internal fun projectNormalAttachment(am: AttachmentModel, messageId: String?): NeutralAttachment {
        
        
        
        
        val stableRef = stableAttachmentRef(am)
        val plan = if (stableRef == null) null else resolveBytePlan(am, legacyOwnerKey = messageId)
        if (plan != null && stableRef != null) refToPlan[stableRef] = plan
        return NeutralProjections.attachment(
            am,
            bytesPresent = plan != null,
            stableRefOverride = stableRef,
        )
    }

    




    private fun stableAttachmentRef(am: AttachmentModel): String? = NeutralProjections.attachmentStableRef(am)

    




    internal fun projectOwnedAttachment(oa: NeutralProjections.OwnedAttachment): NeutralAttachment {
        val plan = resolveBytePlan(oa.model, legacyOwnerKey = oa.model.authorityId?.toString())
        val stableRef = stableAttachmentRef(oa.model)
        if (plan != null && stableRef != null) refToPlan[stableRef] = plan
        return NeutralProjections.attachment(
            oa.model,
            bytesPresent = plan != null,
            owner = oa.owner,
            ownerAuthor = oa.ownerAuthor,
            ownerSentAt = oa.ownerSentAt,
            stableRefOverride = stableRef,
            hostMessageRef = oa.hostMessageId,
        )
    }

    











    private fun resolveBytePlan(
        am: AttachmentModel,
        legacyOwnerKey: String?,
    ): BytePlan? {
        val fileName = am.fileName?.takeIf { it.isNotEmpty() } ?: return null
        
        
        
        if (!EncryptedAttachmentAccess.isUsableKey(am.key)) return null
        
        if (!includeAttachments && !isVoiceNote(am)) return null
        
        am.localId?.takeIf { it.isNotEmpty() }?.let { localId ->
            planForPath(attachmentDir(localId) + fileName)?.let { return it }
        }
        
        legacyOwnerKey?.takeIf { it.isNotEmpty() }?.let { key ->
            planForPath(attachmentDir(key) + fileName)?.let { return it }
        }
        return null 
    }

    
    private fun isVoiceNote(am: AttachmentModel): Boolean =
        am.flags == FLAG_VOICE && am.contentType?.contains("audio") == true

    



    private fun planForPath(path: String): BytePlan? {
        
        
        
        if (!EncryptedAttachmentAccess.hasEncrypted(path)) return null
        return BytePlan(EncryptedAttachmentAccess.encryptedFile(path).absolutePath)
    }


    override fun attachmentByteSource(): AttachmentByteSource = AttachmentByteSource { ref ->
        val plan = refToPlan[ref]
        if (plan == null) {
            
            
            L.w { "[MigrationV2][Export] no byte plan for attachment ref=$ref" }
            null
        } else {
            
            
            val file = File(plan.encryptPath)
            if (!file.exists()) {
                L.w { "[MigrationV2][Export] at-rest ciphertext vanished ref=$ref" }
                null
            } else {
                AttachmentBytes(length = file.length(), stream = FileInputStream(file))
            }
        }
    }
}


private const val FLAG_VOICE = 1


private const val ESTIMATE_SAMPLE_MESSAGES = 50
