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
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import java.io.Closeable
import java.io.File

/**
 * The staging WCDB the restore importer writes into, opened at a dedicated path under the app's files
 * area and never the live `tt_wcdb_database.db`. Once import and verify succeed, [PromoteStep] moves
 * this db file into the real location and installs [stagingKey] as the real cipher key.
 *
 * It carries the same table set and `insert*` call shapes as the live WCDB, so the importer's write
 * seam is reused unchanged, but this file is meant to survive the import and be promoted. Its cipher
 * key mirrors the real DB's policy for the build: the real WCDB key in release, or null in debug,
 * where the real DB is plaintext and there is no key to install.
 *
 * Restore is net-install only, so the target is empty and a plain `insertObject` is correct for every
 * row — no PK collisions and no need for an `insertOrReplaceObject` keyed on `databaseId`.
 *
 * [close] checkpoints, so the rows are durably in the main db file, and closes the handle; it does not
 * delete the staging file, which promote consumes afterwards. [discard] deletes the file and its
 * sidecars when an import is abandoned before promote.
 */
class StagingDatabase private constructor(
    /** Absolute path of the dedicated staging db file (NOT the real DB path). */
    val stagingDbPath: String,
    /**
     * The cipher key the staging db is encrypted with, mirroring the real DB's policy for this build: a
     * real key in release, which [PromoteStep] installs as `wcdb_key.bin`, or null in debug, where
     * staging is plaintext and promote skips the key-install step entirely.
     */
    val stagingKey: ByteArray?,
) : WcdbWriteTarget, Closeable {

    private val db: Database = Database(stagingDbPath).also { database ->
        // Set a cipher key only when this build has one. In debug the staging DB is plaintext, and a
        // key here would make the later keyless open throw Code 26 "file is not a database". The handle
        // opens eagerly so a cipher or IO failure surfaces here.
        stagingKey?.let { database.setCipherKey(it) }
        database.execute("PRAGMA journal_mode")
        L.i { "[MigrationV2][Restore] staging DB opened path=$stagingDbPath keyed=${stagingKey != null}" }
    }

    // Typed table handles — the same set and pattern as the live WCDB.
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

    init {
        // Created but never written: the live schema still carries this table, and promote moves this
        // whole file into place, so a missing table would leave the promoted DB without it.
        db.createTable("group_member_contactor", DBGroupMemberContactorModel)
    }

    private fun <T> table(name: String, binding: com.tencent.wcdb.orm.TableBinding<T>): Table<T> {
        db.createTable(name, binding)
        return db.getTable(name, binding)
    }

    // ── WcdbWriteTarget — identical call shapes to WcdbWriteTargetImpl ──────────────────────────

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
        // The target is empty, so every imported room arrives with databaseId=0. insertOrReplaceObject
        // keys on that PK and would collide every room at rowid 0; a plain insertObject lets SQLite
        // assign distinct rowids while the unique roomId index stays satisfied.
        roomTable.insertObject(model)
    }

    override fun insertReadInfo(model: ReadInfoModel) {
        // Insert-or-replace on the {roomId, uid} composite PK, mirroring the live write. The target is
        // empty so there is nothing to replace, but the semantics keep a re-import safe.
        readInfoTable.insertOrReplaceObject(model)
    }

    override fun insertGroupKey(model: GroupCryptoKeysModel) {
        groupCryptoKeysTable.insertOrReplaceObject(model)
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

    // ── RoomStateReadWrite: post-import derived-state recompute against this staging DB ─────────
    // Every read is a per-room query; a full-table load per room is O(rooms × messages) and OOMs.

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

    // Alerts are rare; the alert rows are the small side of the intersection.
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

    /**
     * Checkpoint the WAL into the main db file, so promote can move that one file without leaving rows
     * pending in `-wal`. [PromoteStep] also moves any surviving sidecars as a second line of defence.
     *
     * @throws MigrationException.RestoreFailed if `truncateCheckpoint` fails. A failed checkpoint means
     *   WAL rows may not be folded into the main db file, so the staging DB is not promote-ready and
     *   the caller must treat this as a hard failure rather than a warning to swallow.
     */
    fun checkpoint() {
        // truncateCheckpoint both merges and truncates the WAL, leaving the main db file
        // self-contained. A failure is surfaced, not swallowed: without it that file is row-incomplete.
        try {
            db.truncateCheckpoint()
        } catch (e: Throwable) {
            L.e { "[MigrationV2][Restore] staging checkpoint failed (DB not promote-ready): ${e.stackTraceToString()}" }
            throw MigrationException.RestoreFailed("staging checkpoint failed; DB not promote-ready", e)
        }
    }

    /**
     * Checkpoint and then close the handle. A failed checkpoint propagates out of [close], so the caller
     * knows the staging DB is not promote-ready; the handle is still closed first, so the file is not
     * left open.
     */
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

    /**
     * Delete the staging db file and every WCDB sidecar. Call when an import is abandoned before
     * promote. Safe to call after [close].
     */
    fun discard() {
        runCatching { db.close() }
        deleteStagingArtifacts(stagingDbPath)
        L.i { "[MigrationV2][Restore] staging DB discarded path=$stagingDbPath" }
    }

    companion object {
        /** Keeps an `IN (...)` list under SQLite's bound-variable limit. */
        private const val SQL_IN_CHUNK = 500

        /**
         * Open a fresh staging db at [stagingDbPath], encrypted with the key this build uses for the
         * real DB rather than a throwaway random one, which keeps the staging and real encryption state
         * coherent through promote: in release the key installed as `wcdb_key.bin` is already the real
         * key, and in debug both are plaintext so promote skips the key step.
         *
         * Any pre-existing file at that path, and its sidecars, is deleted first so the staging db
         * always starts empty.
         */
        fun create(context: Context, stagingDbPath: String): StagingDatabase {
            deleteStagingArtifacts(stagingDbPath)
            File(stagingDbPath).parentFile?.mkdirs()
            return StagingDatabase(stagingDbPath, WCDBKeyManager.cipherKeyForCurrentBuild(context))
        }

        /** Delete the db file and every WCDB sidecar for [path]. */
        fun deleteStagingArtifacts(path: String) {
            val base = File(path)
            val parent = base.parentFile
            listOf("", "-wal", "-shm", "-journal", "-first.material", "-last.material").forEach { suffix ->
                runCatching { File(parent, base.name + suffix).takeIf { it.exists() }?.delete() }
            }
        }
    }
}
