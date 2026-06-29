package com.difft.android.messageserialization.db.store

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.tracedPerf
import com.tencent.wcdb.base.WCDBException
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.mapToMessageId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.difft.app.database.delete
import org.difft.app.database.deleteMessagesPaged
import org.difft.app.database.maxMessageDatabaseId
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.DBSpeechToTextModel
import org.difft.app.database.models.DBTranslateModel
import org.difft.app.database.models.PendingMessageModelNew
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.SpeechToTextModel
import org.difft.app.database.models.TranslateModel
import org.difft.app.database.putMessageIfNotExists
import org.difft.app.database.wcdb
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
@Singleton
class DBMessageStore
@Inject
constructor(
    private val dbRoomStore: DBRoomStore,
) : MessageStore {

    override fun putWhenNonExist(vararg messages: Message) {
        L.d { "[Message] putWhenNonExist size:${messages.size}" }
        val startTime = System.currentTimeMillis()
        try {
            // #971: batch-granularity Performance trace (one start/stop per batch on the
            // ingest thread) so p99 ingest latency is visible without per-message observer cost.
            // Scope = the DB write only; trackRoom (a flow emit) stays outside for clean attribution.
            tracedPerf("wcdb_put_messages_batch", mapOf("count" to messages.size.toLong())) {
                messages.forEach { message ->
                    if (!processingMessageIds.add(message.id)) {
                        L.i { "[Message] putWhenNonExist: ${message.id} already processing, skipping" }
                        return@forEach
                    }
                    try {
                        val room = dbRoomStore.createRoomIfNotExist(message.forWhat)
                        wcdb.putMessageIfNotExists(message, room.readPosition)
                    } finally {
                        processingMessageIds.remove(message.id)
                    }
                }
            }
            // Notify only after a successful batch insert (an insert failure rethrows above and skips this).
            messages.groupBy { it.forWhat.id }.forEach { (roomId, _) ->
                RoomChangeTracker.trackRoom(roomId, RoomChangeType.MESSAGE)
            }
            val tookTime = System.currentTimeMillis() - startTime
            L.i { "[Message] putWhenNonExist batch took ${tookTime}ms for ${messages.size} messages" }
        } catch (e: Exception) {
            L.e { "[Message] Failed to put messages: ${e.stackTraceToString()}" }
            throw e
        }
    }

    companion object {
        private val processingMessageIds = ConcurrentHashMap.newKeySet<String>()

        // #969 in-flight room deletes: a second delete for the SAME room while one is in
        // flight would run a second paged-delete loop with its own snapshot, interleaving
        // page deletes and double-firing trackRoom. We skip the duplicate (the in-flight
        // loop already covers this room) and, if it was a legitimately newer delete, redeem
        // it via pendingRedeleteRoomIds once the in-flight loop releases. Same structure as
        // processingMessageIds above.
        private val deletingRoomIds = ConcurrentHashMap.newKeySet<String>()
        private val pendingRedeleteRoomIds = ConcurrentHashMap.newKeySet<String>()
    }

    override fun deleteMessage(messageIds: List<String>) {
        appScope.launch {
            try {
                wcdb.message.getAllObjects(DBMessageModel.id.`in`(messageIds)).forEach {
                    it.delete()
                    RoomChangeTracker.trackRoom(it.roomId ?: "", RoomChangeType.MESSAGE)
                    L.d { "[Message] delete message success:" + it.id }
                }
            } catch (e: Exception) {
                L.e { "[Message] Failed to delete message: ${e.stackTraceToString()}" }
            }
        }
    }

    override fun removeRoomAndMessages(roomId: String) {
        // #969 per-room guard: take the in-flight mark SYNCHRONOUSLY here (outside appScope.launch)
        // so two rapid calls can't both launch before either adds — putting the add inside the
        // coroutine would race the guard away. A duplicate while one is in flight is skipped; if it
        // was a legitimately newer delete (new messages arrived past the in-flight snapshot), it is
        // re-driven once via pendingRedeleteRoomIds when the in-flight loop releases (ARCH-CRIT-1).
        if (!deletingRoomIds.add(roomId)) {
            pendingRedeleteRoomIds.add(roomId)
            L.i { "[Message] removeRoomAndMessages: $roomId already deleting, queuing redelete" }
            return
        }
        appScope.launch {
            try {
                // Snapshot the upper bound BEFORE deleting anything, so concurrent inserts during
                // the paged delete (room still valid + server still pushing) are NOT swept in. The
                // match set is fixed at snapshot time (databaseId is the monotonic autoincrement PK)
                // → the paged loop is guaranteed to converge. New messages (databaseId > snapshotMax)
                // survive; if the room is still valid it legitimately rebuilds with only those newer
                // messages. (#969)
                val snapshotMax = maxMessageDatabaseId(roomId)

                // Delete the room row FIRST so the conversation disappears from the list
                // immediately — a user-initiated delete should feel instant. Then page-delete
                // its messages. Deleting messages first instead would keep the room visible for the
                // whole paged-delete window (~100ms × pages).
                //
                // Two ways message rows can outlive the room row here: an interrupted paged delete,
                // or — new with #969 — messages that arrived past snapshotMax (deliberately preserved).
                // If the room is still active these are reclaimed when the next incoming message
                // re-creates the room row (the rows re-attach to the rebuilt room). Rows for a room
                // that is NEVER re-created stay as orphans: cleanEmptyRooms only sweeps rooms that
                // still EXIST in the room table, so it does NOT reclaim room-less message rows.
                // True room-less orphan reclamation remains the tracked #909 follow-up.
                //
                // Deleting the room row does not affect the databaseId-bounded message delete below
                // (independent tables, no FK cascade).
                wcdb.room.deleteObjects(DBRoomModel.roomId.eq(roomId))
                deleteMessagesPaged(
                    DBMessageModel.roomId.eq(roomId).and(DBMessageModel.databaseId.le(snapshotMax))
                )
                RoomChangeTracker.trackRoom(roomId, RoomChangeType.MESSAGE)
                L.i { "[Message] remove room and messages success: $roomId (snapshotMax=$snapshotMax)" }
            } catch (e: CancellationException) {
                // Don't swallow cancellation — re-throw to honor structured concurrency and avoid
                // logging a normal scope teardown as an error. The finally below still runs, so the
                // guard is released either way. (#972 review)
                throw e
            } catch (e: Exception) {
                L.e { "[Message] Failed to remove room and messages: ${e.stackTraceToString()}" }
            } finally {
                // Always release the guard — success, exception, or cancellation — otherwise the
                // room could never be deleted again. If a newer delete was queued while this one
                // ran, re-drive it once: it takes a fresh snapshot covering any messages that
                // arrived past this loop's snapshotMax. Idempotent — a no-op if the room is empty.
                deletingRoomIds.remove(roomId)
                if (pendingRedeleteRoomIds.remove(roomId)) {
                    L.i { "[Message] removeRoomAndMessages: re-driving queued redelete for $roomId" }
                    removeRoomAndMessages(roomId)
                }
            }
        }
    }

    override fun updateMessageReaction(
        conversationId: String,
        reaction: Reaction,
        reactionMessageId: String?,
        envelopeBytes: ByteArray?
    ) {
        try {
            val realMessageId = reaction.realSource?.mapToMessageId()?.idValue ?: return
            wcdb.db.runTransaction {
                val currentEmojiReaction = wcdb.reaction.getAllObjects(
                    DBReactionModel.messageId.eq(realMessageId)
                        .and(DBReactionModel.emoji.eq(reaction.emoji))
                        .and(DBReactionModel.uid.eq(reaction.uid))
                ).firstOrNull()
                if (reaction.remove) {
                    if (currentEmojiReaction != null) {
                        if (reaction.originTimestamp > currentEmojiReaction.timeStamp) {
                            wcdb.reaction.deleteObjects(DBReactionModel.databaseId.eq(currentEmojiReaction.databaseId))
                            L.i { "[Message] updateMessageReaction: Remove reaction success. emoji: ${reaction.emoji}, uid: ${reaction.uid}" }
                        } else {
                            L.w { "[Message] updateMessageReaction: Attempt to remove reaction with older timestamp, ignoring." }
                        }
                    } else {
                        L.w { "[Message] updateMessageReaction: No reaction found to remove for messageId: $realMessageId, emoji: ${reaction.emoji}, uid: ${reaction.uid}" }
                        val originalMessageTimeStamp = reaction.realSource?.timestamp
                        if (originalMessageTimeStamp != null && reactionMessageId != null && envelopeBytes != null) {
                            savePendingMessage(reactionMessageId, originalMessageTimeStamp, envelopeBytes)
                        }
                    }
                } else {
                    if (currentEmojiReaction != null) {
                        if (reaction.originTimestamp > currentEmojiReaction.timeStamp) {
                            wcdb.reaction.deleteObjects(DBReactionModel.databaseId.eq(currentEmojiReaction.databaseId))
                            wcdb.reaction.insertObject(
                                ReactionModel().apply {
                                    messageId = realMessageId
                                    emoji = reaction.emoji
                                    uid = reaction.uid
                                    timeStamp = reaction.originTimestamp
                                }
                            )
                            L.i { "[Message] updateMessageReaction: Update reaction success. emoji: ${reaction.emoji}, uid: ${reaction.uid}" }
                        }
                    } else {
                        wcdb.reaction.insertObject(
                            ReactionModel().apply {
                                messageId = realMessageId
                                emoji = reaction.emoji
                                uid = reaction.uid
                                timeStamp = reaction.originTimestamp
                            }
                        )
                        L.i { "[Message] updateMessageReaction: Insert reaction success. emoji: ${reaction.emoji}, uid: ${reaction.uid}" }
                    }
                }
                RoomChangeTracker.trackRoom(conversationId, RoomChangeType.MESSAGE)
                true
            }
        } catch (e: Exception) {
            L.e { "[Message] updateMessageReaction error: ${e.stackTraceToString()}" }
        }
    }

    // ----------------------------------------------------
    // updateMessageTranslateData
    // ----------------------------------------------------
    override suspend fun updateMessageTranslateData(
        conversationId: String,
        messageId: String,
        translateData: TranslateData
    ) {
        val models = wcdb.message.getAllObjects(
            DBMessageModel.roomId.eq(conversationId)
                .and(DBMessageModel.id.eq(messageId))
        )
        if (models.isNotEmpty()) {
            val oldMessage = models[0]
            val oldTranslateData = wcdb.translate.getFirstObject(
                DBTranslateModel.messageId.eq(oldMessage.id)
            )
            if (oldTranslateData != null) {
                oldTranslateData.translateStatus = translateData.translateStatus.status
                oldTranslateData.translatedContentCN = translateData.translatedContentCN
                oldTranslateData.translatedContentEN = translateData.translatedContentEN
                wcdb.translate.updateObject(
                    oldTranslateData,
                    arrayOf(
                        DBTranslateModel.translateStatus,
                        DBTranslateModel.translatedContentCN,
                        DBTranslateModel.translatedContentEN
                    ),
                    DBTranslateModel.databaseId.eq(oldTranslateData.databaseId)
                )
            } else {
                TranslateModel().apply {
                    this.messageId = oldMessage.id
                    this.translateStatus = translateData.translateStatus.status
                    this.translatedContentCN = translateData.translatedContentCN
                    this.translatedContentEN = translateData.translatedContentEN
                }.also {
                    wcdb.translate.insertObject(it)
                }
            }
        }
    }

    override suspend fun updateMessageSpeechToTextData(
        conversationId: String,
        messageId: String,
        speechToTextData: SpeechToTextData
    ) {
        val models = wcdb.message.getAllObjects(
            DBMessageModel.roomId.eq(conversationId)
                .and(DBMessageModel.id.eq(messageId))
        )
        if (models.isNotEmpty()) {
            val oldMessage = models[0]
            val oldSpeechToTextData = wcdb.speechToText.getFirstObject(
                DBSpeechToTextModel.messageId.eq(oldMessage.id)
            )
            if (oldSpeechToTextData != null) {
                oldSpeechToTextData.convertStatus = speechToTextData.convertStatus.status
                oldSpeechToTextData.speechToTextContent = speechToTextData.speechToTextContent
                wcdb.speechToText.updateObject(
                    oldSpeechToTextData,
                    arrayOf(
                        DBSpeechToTextModel.convertStatus,
                        DBSpeechToTextModel.speechToTextContent,
                    ),
                    DBSpeechToTextModel.databaseId.eq(oldSpeechToTextData.databaseId)
                )
            } else {
                SpeechToTextModel().apply {
                    this.messageId = oldMessage.id
                    this.convertStatus = speechToTextData.convertStatus.status
                    this.speechToTextContent = speechToTextData.speechToTextContent
                }.also {
                    wcdb.speechToText.insertObject(it)
                }
            }
        }
    }

    override fun deleteDatabase() {
        wcdb.deleteDatabaseFile()
    }

    // ----------------------------------------------------
    // selectableMessageCount
    // ----------------------------------------------------
    fun selectableMessageCount(forWhat: For): Int {
        return wcdb.message.getValue(
            DBMessageModel.databaseId.count(),
            DBMessageModel.roomId.eq(forWhat.id)
                .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER, MessageModel.TYPE_UNSUPPORTED))
        )?.int ?: 0
    }

    override suspend fun updateMessageReadTime(conversationId: String, readMaxTimestamp: Long) {
        val expression = DBMessageModel.roomId.eq(conversationId)
            .and(DBMessageModel.readTime.eq(0L).or(DBMessageModel.readTime.isNull()))
            .and(DBMessageModel.systemShowTimestamp.le(readMaxTimestamp).or(DBMessageModel.systemShowTimestamp.eq(readMaxTimestamp)))
        // #909 #4: drop the unbounded getAllObjects load — updateValue is a no-op on an
        // empty match set, so the isNotEmpty() guard was pure overhead. No load needed.
        wcdb.message.updateValue(
            readMaxTimestamp,
            DBMessageModel.readTime,
            expression
        )
        L.i { "[Message] updateMessageReadTime conversationId:${conversationId} readMaxTimestamp:${readMaxTimestamp}" }
    }

    override fun savePendingMessage(messageId: String, originalMessageTimeStamp: Long, messageEnvelopBytes: ByteArray) {
        PendingMessageModelNew().apply {
            this.messageId = messageId
            this.originalMessageTimeStamp = originalMessageTimeStamp
            this.messageEnvelopBytes = messageEnvelopBytes
        }.run {
            try {
                wcdb.pendingMessageNew.insertOrReplaceObject(this)
            } catch (e: WCDBException) {
                L.e { "[Message] savePendingMessage error: ${e.stackTraceToString()}" }
            }
        }
    }
}