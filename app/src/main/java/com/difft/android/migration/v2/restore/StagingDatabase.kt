package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.importer.LatestMessagePreviewInfo
import com.difft.android.migration.v2.importer.RoomDerivedState
import com.difft.android.migration.v2.importer.RoomStateSupport
import com.difft.android.migration.v2.importer.WcdbWriteTarget
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.core.Database
import org.difft.app.database.WCDBKeyManager
import com.tencent.wcdb.core.Table
import com.tencent.wcdb.winq.Order
import org.difft.app.database.models.AttachmentModel
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBForwardContextModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.DBGroupCryptoKeysModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.difft.app.database.models.DBQuoteModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBReadInfoModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.DBSharedContactModel
import org.difft.app.database.models.DBSharedContactPhoneModel
import org.difft.app.database.models.PendingMessageModelNew
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import java.io.Closeable
import java.io.File


















class StagingDatabase private constructor(
    
    val stagingDbPath: String,
    




    val stagingKey: ByteArray?,
) : WcdbWriteTarget, Closeable {

    private val db: Database = Database(stagingDbPath).also { database ->
        
        
        
        stagingKey?.let { database.setCipherKey(it) }
        database.execute("PRAGMA journal_mode")
        L.i { "[MigrationV2][Restore] staging DB opened path=$stagingDbPath keyed=${stagingKey != null}" }
    }

    
    private val messageTable: Table<MessageModel> = table("message", DBMessageModel)
    private val quoteTable: Table<QuoteModel> = table("quote", DBQuoteModel)
    private val forwardContextTable: Table<ForwardContextModel> = table("forward_context", DBForwardContextModel)
    private val forwardTable: Table<ForwardModel> = table("forward", DBForwardModel)
    private val mentionTable: Table<MentionModel> = table("mention", DBMentionModel)
    private val reactionTable: Table<ReactionModel> = table("reaction", DBReactionModel)
    private val roomTable: Table<RoomModel> = table("room", DBRoomModel)
    private val attachmentTable: Table<AttachmentModel> = table("attachment", DBAttachmentModel)
    private val groupCryptoKeysTable: Table<GroupCryptoKeysModel> = table("group_crypto_keys", DBGroupCryptoKeysModel)
    private val sharedContactTable: Table<SharedContactModel> = table("shared_contact", DBSharedContactModel)
    private val sharedContactPhoneTable: Table<SharedContactPhoneModel> =
        table("shared_contact_phone", DBSharedContactPhoneModel)
    private val readInfoTable: Table<ReadInfoModel> = table("read_info", DBReadInfoModel)
    
    private val pendingMessageNew: Table<PendingMessageModelNew> = table("pending_message_new", DBPendingMessageModelNew)

    init {
        
        
        db.createTable("group_member_contactor", DBGroupMemberContactorModel)
    }

    private fun <T> table(name: String, binding: com.tencent.wcdb.orm.TableBinding<T>): Table<T> {
        db.createTable(name, binding)
        return db.getTable(name, binding)
    }

    

    override fun runInTransaction(block: () -> Unit) {
        db.runTransaction { _ ->
            block()
            true
        }
    }

    override fun insertQuote(model: QuoteModel): Long {
        quoteTable.insertObject(model)
        return model.databaseId.toLong()
    }

    override fun insertForwardContext(model: ForwardContextModel): Long {
        forwardContextTable.insertObject(model)
        return model.databaseId.toLong()
    }

    override fun insertForward(model: ForwardModel): Long {
        forwardTable.insertObject(model)
        return model.databaseId.toLong()
    }

    override fun insertMessage(model: MessageModel) {
        messageTable.insertObject(model)
    }

    override fun insertAttachment(model: AttachmentModel) {
        attachmentTable.insertObject(model)
    }

    override fun insertMention(model: MentionModel) {
        mentionTable.insertObject(model)
    }

    override fun insertReaction(model: ReactionModel) {
        reactionTable.insertObject(model)
    }

    override fun insertSharedContact(model: SharedContactModel): Long {
        sharedContactTable.insertObject(model)
        return model.databaseId.toLong()
    }

    override fun insertSharedContactPhone(model: SharedContactPhoneModel) {
        sharedContactPhoneTable.insertObject(model)
    }

    override fun upsertRoom(model: RoomModel) {
        
        
        
        roomTable.insertObject(model)
    }

    override fun insertReadInfo(model: ReadInfoModel) {
        
        
        readInfoTable.insertOrReplaceObject(model)
    }

    override fun insertGroupKey(model: GroupCryptoKeysModel) {
        groupCryptoKeysTable.insertOrReplaceObject(model)
    }

    override fun insertPendingEnvelope(model: PendingMessageModelNew) {
        
        pendingMessageNew.insertOrReplaceObject(model)
    }

    override fun markReadUpTo(roomId: String, maxServerTime: Long, readAt: Long, selfUid: String) {
        messageTable.updateValue(
            readAt,
            DBMessageModel.readTime,
            DBMessageModel.roomId.eq(roomId)
                .and(DBMessageModel.readTime.eq(0L))
                .and(DBMessageModel.fromWho.notEq(selfUid))
                .and(DBMessageModel.systemShowTimestamp.le(maxServerTime)),
        )
    }

    override fun markAttachmentBytesAbsent(attachmentId: String) {
        attachmentTable.updateValue(0, DBAttachmentModel.status, DBAttachmentModel.id.eq(attachmentId))
    }

    
    

    override fun roomIdsWithMessages(): List<String> =
        roomTable.allObjects.mapNotNull { it.roomId?.takeIf { id -> id.isNotEmpty() } }
            .filter { messageTable.getFirstObject(DBMessageModel.roomId.eq(it)) != null }

    override fun roomReadPosition(roomId: String): Long =
        roomTable.getFirstObject(DBRoomModel.roomId.eq(roomId))?.readPosition ?: 0L

    override fun latestMessagePreviewInfo(roomId: String): LatestMessagePreviewInfo? {
        val latest = messageTable.getFirstObject(
            DBMessageModel.roomId.eq(roomId),
            DBMessageModel.systemShowTimestamp.order(Order.Desc),
        ) ?: return null
        val fcPk = RoomStateSupport.forwardContextPk(latest)
        return RoomStateSupport.previewInfoOf(
            latest,
            attachmentContentType = attachmentTable.getFirstObject(DBAttachmentModel.messageId.eq(latest.id))?.contentType,
            hasSharedContact = sharedContactTable.getFirstObject(DBSharedContactModel.messageId.eq(latest.id)) != null,
            hasForwardContext = fcPk != null && forwardContextTable.getFirstObject(DBForwardContextModel.databaseId.eq(fcPk)) != null,
        )
    }

    override fun unreadMessageIds(roomId: String, readPosition: Long, selfUid: String): List<String> =
        RoomStateSupport.unreadMessageIds(
            roomId,
            readPosition,
            selfUid,
            messageTable.getAllObjects(DBMessageModel.roomId.eq(roomId).and(DBMessageModel.systemShowTimestamp.gt(readPosition))),
        )

    override fun mentionUidsForMessages(messageIds: List<String>, selfUid: String): List<String> =
        RoomStateSupport.mentionUidsForMessages(
            messageIds,
            selfUid,
            messageIds.chunked(SQL_IN_CHUNK).flatMap { chunk -> mentionTable.getAllObjects(DBMentionModel.messageId.`in`(*chunk.toTypedArray())) },
        )

    override fun hasFailedOutgoingMessage(roomId: String): Boolean =
        RoomStateSupport.hasFailedOutgoingMessage(
            roomId,
            messageTable.getAllObjects(DBMessageModel.roomId.eq(roomId).and(DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_FAILED))),
        )

    
    override fun hasCriticalAlert(messageIds: List<String>): Boolean =
        RoomStateSupport.hasCriticalAlert(
            messageIds,
            messageTable.getAllObjects(DBMessageModel.criticalAlertType.eq(CRITICAL_ALERT_TYPE_ALERT)),
        )

    override fun updateRoomDerivedState(roomId: String, state: RoomDerivedState) {
        roomTable.updateRow(
            arrayOf(
                Value(state.lastDisplayContent),
                Value(state.lastActiveTime),
                Value(state.unreadMessageNum),
                Value(state.mentionType),
                Value(state.sendStatus),
                Value(state.criticalAlertType),
            ),
            arrayOf(
                DBRoomModel.lastDisplayContent,
                DBRoomModel.lastActiveTime,
                DBRoomModel.unreadMessageNum,
                DBRoomModel.mentionType,
                DBRoomModel.sendStatus,
                DBRoomModel.criticalAlertType,
            ),
            DBRoomModel.roomId.eq(roomId),
        )
    }

    







    fun checkpoint() {
        
        
        try {
            db.truncateCheckpoint()
        } catch (e: Throwable) {
            L.e { "[MigrationV2][Restore] staging checkpoint failed (DB not promote-ready): ${e.stackTraceToString()}" }
            throw MigrationException.RestoreFailed("staging checkpoint failed; DB not promote-ready", e)
        }
    }

    




    override fun close() {
        var checkpointFailure: Throwable? = null
        try {
            checkpoint()
        } catch (e: Throwable) {
            checkpointFailure = e
        }
        runCatching { db.close() }
            .onFailure { L.w { "[MigrationV2][Restore] staging DB close failed: ${it.message}" } }
        if (checkpointFailure != null) {
            L.e { "[MigrationV2][Restore] staging DB closed but checkpoint failed → not promote-ready path=$stagingDbPath" }
            throw checkpointFailure
        }
        L.i { "[MigrationV2][Restore] staging DB closed path=$stagingDbPath (file retained for promote)" }
    }

    



    fun discard() {
        runCatching { db.close() }
        deleteStagingArtifacts(stagingDbPath)
        L.i { "[MigrationV2][Restore] staging DB discarded path=$stagingDbPath" }
    }

    companion object {
        
        private const val SQL_IN_CHUNK = 500

        








        fun create(context: Context, stagingDbPath: String): StagingDatabase {
            deleteStagingArtifacts(stagingDbPath)
            File(stagingDbPath).parentFile?.mkdirs()
            return StagingDatabase(stagingDbPath, WCDBKeyManager.cipherKeyForCurrentBuild(context))
        }

        
        fun deleteStagingArtifacts(path: String) {
            val base = File(path)
            val parent = base.parentFile
            listOf("", "-wal", "-shm", "-journal", "-first.material", "-last.material").forEach { suffix ->
                runCatching { File(parent, base.name + suffix).takeIf { it.exists() }?.delete() }
            }
        }
    }
}
