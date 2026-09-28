package com.difft.android.migration.v2

import com.difft.android.migration.v2.importer.LatestMessagePreviewInfo
import com.difft.android.migration.v2.importer.RoomDerivedState
import com.difft.android.migration.v2.importer.RoomStateSupport
import com.difft.android.migration.v2.importer.WcdbWriteTarget
import org.difft.app.database.models.AttachmentModel
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
 * In-memory [WcdbWriteTarget] standing in for a real second WCDB instance in the Robolectric
 * round-trip test. Faithfully reproduces the two WCDB ORM semantics the importer depends on:
 *
 *  1. **Autoincrement PK assignment** — `insertObject` assigns the next monotonic `databaseId` and
 *     writes it back into the model (matching `WCDBExtensions.convertToMessageModel`, which reads
 *     `quoteModel.databaseId` immediately after insert). The importer's FK reconnection relies on
 *     exactly this returned value.
 *  2. **Transaction grouping** — `runInTransaction` just runs the block (no rollback needed in the
 *     happy-path POC test); batching correctness is observable via the call grouping.
 *
 * It is NOT a SQL engine: it stores inserted models in lists so the test can re-project them back
 * into neutral records and feed both sides to [com.difft.android.migration.v2.verify.RoundTripVerifier].
 * The native WCDB path ([com.difft.android.migration.v2.importer.WcdbWriteTargetImpl]) is the same
 * shape and is exercised by the on-device POC Activity (plan §4 task 7) where the native lib loads.
 */
class InMemoryWcdbWriteTarget : WcdbWriteTarget {

    val messages = ArrayList<MessageModel>()
    val attachments = ArrayList<AttachmentModel>()
    val mentions = ArrayList<MentionModel>()
    val reactions = ArrayList<ReactionModel>()
    val quotes = ArrayList<QuoteModel>()
    val forwardContexts = ArrayList<ForwardContextModel>()
    val forwards = ArrayList<ForwardModel>()
    val rooms = ArrayList<RoomModel>()
    val groupKeys = ArrayList<GroupCryptoKeysModel>()
    val sharedContacts = ArrayList<SharedContactModel>()
    val sharedContactPhones = ArrayList<SharedContactPhoneModel>()
    val readInfos = ArrayList<ReadInfoModel>()

    var transactionCount = 0
        private set

    // Per-table monotonic autoincrement counters (mirror SQLite rowid behaviour). Int post-#913:
    // the real autoincrement PK column is now Int, so the fake mirrors that and widens to the
    // portable Long the WcdbWriteTarget ID-remap contract returns.
    private var quoteSeq = 0
    private var fcSeq = 0
    private var forwardSeq = 0
    private var sharedContactSeq = 0

    override fun runInTransaction(block: () -> Unit) {
        transactionCount++
        block()
    }

    override fun insertQuote(model: QuoteModel): Long {
        model.databaseId = ++quoteSeq
        quotes.add(model)
        return model.databaseId.toLong()
    }

    override fun insertForwardContext(model: ForwardContextModel): Long {
        model.databaseId = ++fcSeq
        forwardContexts.add(model)
        return model.databaseId.toLong()
    }

    override fun insertForward(model: ForwardModel): Long {
        model.databaseId = ++forwardSeq
        forwards.add(model)
        return model.databaseId.toLong()
    }

    override fun insertMessage(model: MessageModel) {
        messages.add(model)
    }

    override fun insertAttachment(model: AttachmentModel) {
        attachments.add(model)
    }

    override fun insertMention(model: MentionModel) {
        mentions.add(model)
    }

    override fun insertReaction(model: ReactionModel) {
        reactions.add(model)
    }

    override fun insertSharedContact(model: SharedContactModel): Long {
        model.databaseId = ++sharedContactSeq
        sharedContacts.add(model)
        return model.databaseId.toLong()
    }

    override fun insertSharedContactPhone(model: SharedContactPhoneModel) {
        sharedContactPhones.add(model)
    }

    override fun upsertRoom(model: RoomModel) {
        rooms.removeAll { it.roomId == model.roomId }
        rooms.add(model)
    }

    override fun insertReadInfo(model: ReadInfoModel) {
        // insert-or-replace on the {roomId, uid} composite PK (mirrors the real WCDB write).
        readInfos.removeAll { it.roomId == model.roomId && it.uid == model.uid }
        readInfos.add(model)
    }

    override fun insertGroupKey(model: GroupCryptoKeysModel) {
        groupKeys.removeAll { it.gid == model.gid }
        groupKeys.add(model)
    }

    override fun markReadUpTo(roomId: String, maxServerTime: Long, readAt: Long, selfUid: String) {
        // Same predicate as the SQL write-once UPDATE: only rows with no read time yet, incoming
        // only, at or below the rung. The -1 sentinel fails `readTime == 0L` and stays put.
        messages.filter {
            it.roomId == roomId && it.readTime == 0L && it.fromWho != selfUid && it.systemShowTimestamp <= maxServerTime
        }.forEach { it.readTime = readAt }
    }

    override fun markAttachmentBytesAbsent(attachmentId: String) {
        attachments.filter { it.id == attachmentId }.forEach { it.status = 0 }
    }

    // ── RoomStateReadWrite (Fix A): delegate reads to the shared support; update mutates the row ─────

    override fun roomIdsWithMessages(): List<String> =
        RoomStateSupport.roomIdsWithMessages(messages)

    override fun roomReadPosition(roomId: String): Long =
        rooms.firstOrNull { it.roomId == roomId }?.readPosition ?: 0L

    override fun latestMessagePreviewInfo(roomId: String): LatestMessagePreviewInfo? =
        RoomStateSupport.latestMessagePreviewInfo(roomId, messages, attachments, sharedContacts, forwardContexts)

    override fun unreadMessageIds(roomId: String, readPosition: Long, selfUid: String): List<String> =
        RoomStateSupport.unreadMessageIds(roomId, readPosition, selfUid, messages)

    override fun mentionUidsForMessages(messageIds: List<String>, selfUid: String): List<String> =
        RoomStateSupport.mentionUidsForMessages(messageIds, selfUid, mentions)

    override fun hasFailedOutgoingMessage(roomId: String): Boolean =
        RoomStateSupport.hasFailedOutgoingMessage(roomId, messages)

    override fun hasCriticalAlert(messageIds: List<String>): Boolean =
        RoomStateSupport.hasCriticalAlert(messageIds, messages)

    override fun updateRoomDerivedState(roomId: String, state: RoomDerivedState) {
        rooms.firstOrNull { it.roomId == roomId }?.apply {
            lastDisplayContent = state.lastDisplayContent
            lastActiveTime = state.lastActiveTime
            unreadMessageNum = state.unreadMessageNum
            mentionType = state.mentionType
            sendStatus = state.sendStatus
            criticalAlertType = state.criticalAlertType
        }
    }
}
