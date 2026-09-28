package com.difft.android.migration.v2.importer

import com.tencent.wcdb.base.Value
import com.tencent.wcdb.base.WCDBException
import com.tencent.wcdb.core.Database
import org.difft.app.database.WCDB
import org.difft.app.database.models.AttachmentModel
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel

/**
 * The narrow DB-write surface [NeutralImporter] needs against a target database, and the test seam
 * that lets the round-trip validation run the whole engine under Robolectric: the WCDB ORM loads its
 * native library in a static initializer, which throws `UnsatisfiedLinkError` on the JVM test
 * classpath, so an in-memory fake reproducing WCDB's autoincrement-PK semantics stands in.
 *
 * Every `insert*` returns the new local autoincrement PK the target assigned — WCDB writes the
 * generated rowid back into `model.databaseId`. The importer reconnects FKs from these; source local
 * PKs are not portable and are never reused.
 */
interface WcdbWriteTarget : RoomStateReadWrite {

    /** Run [block] inside a single DB transaction (batched commit). */
    fun runInTransaction(block: () -> Unit)

    /** Insert a quote row; returns the new local PK (`databaseId`). */
    fun insertQuote(model: QuoteModel): Long

    /** Insert a forward-context row; returns the new local PK. */
    fun insertForwardContext(model: ForwardContextModel): Long

    /** Insert a forward row; returns the new local PK. */
    fun insertForward(model: ForwardModel): Long

    /** Insert a message row (FKs already remapped to target-local PKs). */
    fun insertMessage(model: MessageModel)

    /** Insert an attachment row (references its message by stable string id, no PK remap). */
    fun insertAttachment(model: AttachmentModel)

    /** Insert a mention row. */
    fun insertMention(model: MentionModel)

    /** Insert a reaction row. */
    fun insertReaction(model: ReactionModel)

    /** Insert a contact-card row; returns the new local PK the phone rows reference. */
    fun insertSharedContact(model: SharedContactModel): Long

    /** Insert a shared-contact phone row (FK = the contact's target-local PK). */
    fun insertSharedContactPhone(model: SharedContactPhoneModel)

    /** Insert / update a room row (conversation-local state). */
    fun upsertRoom(model: RoomModel)

    /**
     * Insert or replace a `read_info` row (another participant's read position), keyed on the
     * {roomId, uid} composite PK so a duplicate overwrites rather than collides.
     */
    fun insertReadInfo(model: ReadInfoModel)

    /** Insert a group crypto-key row (string PK, no autoincrement). */
    fun insertGroupKey(model: GroupCryptoKeysModel)

    /**
     * Expand one rung of a read ladder over [roomId]'s incoming messages: set `readTime = readAt` on
     * every row at or below [maxServerTime] that has none yet. The mirror of the live path's
     * `DBMessageStore.updateMessageReadTime`, including its write-once `readTime = 0` guard, so
     * applying rungs by ascending [readAt] leaves each row with the smallest covering read time.
     *
     * The guard is `readTime = 0` exactly, never `<= 0`: the migration NOT_STARTED sentinel (-1) is a
     * decision the source made and must survive the expansion untouched.
     */
    fun markReadUpTo(roomId: String, maxServerTime: Long, readAt: Long, selfUid: String)

    /**
     * Demote the attachment row [attachmentId] to "no bytes here" — status 0, the value the importer
     * writes for a pointer-only row. The row is inserted optimistically, before its byte frame is
     * known to have landed; when it did not, this makes the app re-download instead of opening a file
     * that does not exist.
     */
    fun markAttachmentBytesAbsent(attachmentId: String)
}

/**
 * [WcdbWriteTarget] backed by a real [WCDB] instance — the staging DB, distinct from the live one.
 * Each `insert*` delegates to the ORM's `insertObject`, which writes the assigned autoincrement rowid
 * back into `model.databaseId`, and that value is returned for FK reconnection.
 */
class WcdbWriteTargetImpl(private val target: WCDB) : WcdbWriteTarget {

    override fun runInTransaction(block: () -> Unit) = target.db.runRollingBack(block)

    override fun insertQuote(model: QuoteModel): Long {
        target.quote.insertObject(model)
        // databaseId is the local autoincrement Int PK; widen it to the Long this interface returns.
        return model.databaseId.toLong()
    }

    override fun insertForwardContext(model: ForwardContextModel): Long {
        target.forwardContext.insertObject(model)
        return model.databaseId.toLong()
    }

    override fun insertForward(model: ForwardModel): Long {
        target.forward.insertObject(model)
        return model.databaseId.toLong()
    }

    override fun insertMessage(model: MessageModel) {
        target.message.insertObject(model)
    }

    override fun insertAttachment(model: AttachmentModel) {
        target.attachment.insertObject(model)
    }

    override fun insertMention(model: MentionModel) {
        target.mention.insertObject(model)
    }

    override fun insertReaction(model: ReactionModel) {
        target.reaction.insertObject(model)
    }

    override fun insertSharedContact(model: SharedContactModel): Long {
        target.sharedContact.insertObject(model)
        // databaseId is the local autoincrement Int PK; widen it to the Long the phone FK uses.
        return model.databaseId.toLong()
    }

    override fun insertSharedContactPhone(model: SharedContactPhoneModel) {
        target.sharedContactPhone.insertObject(model)
    }

    override fun upsertRoom(model: RoomModel) {
        // RoomModel's PK is the autoincrement databaseId, so insertOrReplace over a batch of fresh
        // models (all databaseId=0) risks collapsing them into one row (#914). The staging target is
        // empty and roomId is unique, so a plain insertObject is the safe write.
        target.room.insertObject(model)
    }

    override fun insertReadInfo(model: ReadInfoModel) {
        // Insert-or-replace on the {roomId, uid} composite PK, mirroring the live write.
        target.readInfo.insertOrReplaceObject(model)
    }

    override fun insertGroupKey(model: GroupCryptoKeysModel) {
        target.groupCryptoKeys.insertOrReplaceObject(model)
    }

    override fun markReadUpTo(roomId: String, maxServerTime: Long, readAt: Long, selfUid: String) {
        target.message.updateValue(
            readAt,
            DBMessageModel.readTime,
            DBMessageModel.roomId.eq(roomId)
                .and(DBMessageModel.readTime.eq(0L))
                .and(DBMessageModel.fromWho.notEq(selfUid))
                .and(DBMessageModel.systemShowTimestamp.le(maxServerTime)),
        )
    }

    override fun markAttachmentBytesAbsent(attachmentId: String) {
        target.attachment.updateValue(0, DBAttachmentModel.status, DBAttachmentModel.id.eq(attachmentId))
    }

    // ── RoomStateReadWrite: delegate reads to the shared support over this WCDB's rows ──────────

    override fun roomIdsWithMessages(): List<String> =
        RoomStateSupport.roomIdsWithMessages(target.message.allObjects)

    override fun roomReadPosition(roomId: String): Long =
        target.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.readPosition ?: 0L

    override fun latestMessagePreviewInfo(roomId: String): LatestMessagePreviewInfo? =
        RoomStateSupport.latestMessagePreviewInfo(
            roomId,
            target.message.allObjects,
            target.attachment.allObjects,
            target.sharedContact.allObjects,
            target.forwardContext.allObjects,
        )

    override fun unreadMessageIds(roomId: String, readPosition: Long, selfUid: String): List<String> =
        RoomStateSupport.unreadMessageIds(roomId, readPosition, selfUid, target.message.allObjects)

    override fun mentionUidsForMessages(messageIds: List<String>, selfUid: String): List<String> =
        RoomStateSupport.mentionUidsForMessages(messageIds, selfUid, target.mention.allObjects)

    override fun hasFailedOutgoingMessage(roomId: String): Boolean =
        RoomStateSupport.hasFailedOutgoingMessage(roomId, target.message.getAllObjects(DBMessageModel.roomId.eq(roomId)))

    // Alerts are rare; the alert rows are the small side of the intersection.
    override fun hasCriticalAlert(messageIds: List<String>): Boolean =
        RoomStateSupport.hasCriticalAlert(
            messageIds,
            target.message.getAllObjects(DBMessageModel.criticalAlertType.eq(CRITICAL_ALERT_TYPE_ALERT)),
        )

    override fun updateRoomDerivedState(roomId: String, state: RoomDerivedState) {
        target.room.updateRow(
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
}

/**
 * Runs [block] in a transaction that is rolled back on ANY failure. WCDB itself rolls back only on a
 * `false` return or a [WCDBException] out of the callback; any other exception crosses into native
 * code with the transaction's fate undefined. So the block's own failure is caught here, the
 * callback answers `false` (a defined rollback), and the failure is rethrown in place of the generic
 * exception WCDB raises for that `false`.
 */
internal fun Database.runRollingBack(block: () -> Unit) {
    var failure: Exception? = null
    try {
        runTransaction { _ ->
            try {
                block()
                true
            } catch (e: Exception) {
                failure = e
                false
            }
        }
    } catch (e: WCDBException) {
        throw failure ?: e
    }
}
