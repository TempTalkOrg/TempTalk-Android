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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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


@Suppress("BlockingWcdbInSuspend")
@Singleton
class DBMessageStore
@Inject
constructor(
    private val dbRoomStore: DBRoomStore,
) : MessageStore {

    






    private val translateWriteMutex = Mutex()

    override fun putWhenNonExist(vararg messages: Message): Int {
        L.d { "[Message] putWhenNonExist size:${messages.size}" }
        val startTime = System.currentTimeMillis()
        var inserted = 0
        try {
            
            
            
            tracedPerf("wcdb_put_messages_batch", mapOf("count" to messages.size.toLong())) {
                messages.forEach { message ->
                    if (!processingMessageIds.add(message.id)) {
                        L.i { "[Message] putWhenNonExist: ${message.id} already processing, skipping" }
                        return@forEach
                    }
                    try {
                        val room = dbRoomStore.createRoomIfNotExist(message.forWhat)
                        if (wcdb.putMessageIfNotExists(message, room.readPosition)) inserted++
                    } finally {
                        processingMessageIds.remove(message.id)
                    }
                }
            }
            
            messages.groupBy { it.forWhat.id }.forEach { (roomId, _) ->
                RoomChangeTracker.trackRoom(roomId, RoomChangeType.MESSAGE)
            }
            val tookTime = System.currentTimeMillis() - startTime
            if (tookTime > SLOW_INSERT_MS || messages.size > 1) {
                L.i { "[Message] putWhenNonExist batch took ${tookTime}ms for ${messages.size} messages" }
            }
            return inserted
        } catch (e: Exception) {
            L.e { "[Message] Failed to put messages: ${e.stackTraceToString()}" }
            throw e
        }
    }

    companion object {
        private val processingMessageIds = ConcurrentHashMap.newKeySet<String>()

        
        
        private val deletingRoomIds = ConcurrentHashMap.newKeySet<String>()
        private val pendingRedeleteRoomIds = ConcurrentHashMap<String, () -> Boolean>()
        private val allowLegacyDelete: () -> Boolean = { true }
    }

    override fun deleteMessage(messageIds: List<String>) {
        appScope.launch {
            
            
            if (wcdb.isDbInaccessible) {
                L.w { "[Message] skip deleteMessage, DB inaccessible" }
                return@launch
            }
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

    override fun removeRoomAndMessages(roomId: String, commitAllowed: (() -> Boolean)?) {
        if (commitAllowed?.invoke() == false) return
        
        
        if (!deletingRoomIds.add(roomId)) {
            
            pendingRedeleteRoomIds.compute(roomId) { _, queued ->
                if (queued === allowLegacyDelete || commitAllowed == null) allowLegacyDelete else commitAllowed
            }
            L.i { "[Message] removeRoomAndMessages: $roomId already deleting, queuing redelete" }
            return
        }
        appScope.launch {
            try {
                
                if (commitAllowed?.invoke() == false) return@launch
                
                
                if (wcdb.isDbInaccessible) {
                    L.w { "[Message] skip removeRoomAndMessages, DB inaccessible: $roomId" }
                    return@launch
                }
                
                
                
                
                
                
                val snapshotMax = maxMessageDatabaseId(roomId)

                
                
                
                
                if (commitAllowed?.invoke() == false) return@launch
                wcdb.room.deleteObjects(DBRoomModel.roomId.eq(roomId))
                deleteMessagesPaged(
                    DBMessageModel.roomId.eq(roomId).and(DBMessageModel.databaseId.le(snapshotMax)),
                    commitAllowed = commitAllowed,
                )
                if (commitAllowed?.invoke() == false) return@launch
                RoomChangeTracker.trackRoom(roomId, RoomChangeType.MESSAGE)
                L.i { "[Message] remove room and messages success: $roomId (snapshotMax=$snapshotMax)" }
            } catch (e: CancellationException) {
                
                
                
                throw e
            } catch (e: Exception) {
                L.e { "[Message] Failed to remove room and messages: ${e.stackTraceToString()}" }
            } finally {
                
                
                
                
                deletingRoomIds.remove(roomId)
                val queuedGuard = pendingRedeleteRoomIds.remove(roomId)
                if (queuedGuard != null) {
                    L.i { "[Message] removeRoomAndMessages: re-driving queued redelete for $roomId" }
                    removeRoomAndMessages(roomId, queuedGuard)
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
                
                val targetMessage = wcdb.message.getFirstObject(DBMessageModel.id.eq(realMessageId))
                if (targetMessage == null) {
                    
                    if (reactionMessageId != null && envelopeBytes != null) {
                        savePendingMessage(reactionMessageId, reaction.realSource.timestamp, envelopeBytes)
                        L.i { "[Message] updateMessageReaction: target $realMessageId absent, defer via savePendingMessage." }
                    } else {
                        L.w { "[Message] updateMessageReaction: target $realMessageId absent and no envelope to defer, drop." }
                    }
                    return@runTransaction true
                }
                if (targetMessage.roomId != conversationId) {
                    L.w { "[Message] updateMessageReaction: target $realMessageId belongs to room=${targetMessage.roomId} != conversation=$conversationId, drop (cross-conversation injection)" }
                    return@runTransaction true
                }
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

    
    
    
    override suspend fun updateMessageTranslateData(
        conversationId: String,
        messageId: String,
        translateData: TranslateData
    ) {
        if (!translateData.translateStatus.isPersistable) {
            L.w { "[Translate] skip non-persistable status=${translateData.translateStatus} messageId=$messageId" }
            return
        }
        
        
        if (wcdb.isDbInaccessible) {
            L.w { "[Translate] skip single write, DB inaccessible messageId=$messageId" }
            return
        }
        translateWriteMutex.withLock {
            val models = wcdb.message.getAllObjects(
                DBMessageModel.roomId.eq(conversationId)
                    .and(DBMessageModel.id.eq(messageId))
            )
            if (models.isEmpty()) return
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

    override suspend fun updateMessageTranslateDataBatch(
        conversationId: String,
        dataByMessageId: Map<String, TranslateData>
    ) {
        if (dataByMessageId.isEmpty()) return
        val persistable = dataByMessageId.filterValues { it.translateStatus.isPersistable }
        val skipped = dataByMessageId.size - persistable.size
        if (skipped > 0) {
            L.w { "[Translate] batch dropped $skipped non-persistable entries of ${dataByMessageId.size}" }
        }
        if (persistable.isEmpty()) return
        
        if (wcdb.isDbInaccessible) {
            L.w { "[Translate] skip batch write of ${persistable.size}, DB inaccessible" }
            return
        }

        translateWriteMutex.withLock {
            
            
            val existingIds = wcdb.message.getOneColumnString(
                DBMessageModel.id,
                DBMessageModel.roomId.eq(conversationId)
                    .and(DBMessageModel.id.`in`(*persistable.keys.toTypedArray()))
            ).filterNotNull().toSet()
            if (existingIds.isEmpty()) {
                L.w { "[Translate] batch found no live messages of ${persistable.size} in conversation" }
                return
            }

            
            val existingRows = wcdb.translate.getAllObjects(
                DBTranslateModel.messageId.`in`(*existingIds.toTypedArray())
            ).associateBy { it.messageId }

            var updated = 0
            var inserted = 0
            
            
            persistable.forEach { (messageId, translateData) ->
                if (messageId !in existingIds) return@forEach
                val existing = existingRows[messageId]
                if (existing != null) {
                    existing.translateStatus = translateData.translateStatus.status
                    existing.translatedContentCN = translateData.translatedContentCN
                    existing.translatedContentEN = translateData.translatedContentEN
                    wcdb.translate.updateObject(
                        existing,
                        arrayOf(
                            DBTranslateModel.translateStatus,
                            DBTranslateModel.translatedContentCN,
                            DBTranslateModel.translatedContentEN
                        ),
                        DBTranslateModel.databaseId.eq(existing.databaseId)
                    )
                    updated++
                } else {
                    TranslateModel().apply {
                        this.messageId = messageId
                        this.translateStatus = translateData.translateStatus.status
                        this.translatedContentCN = translateData.translatedContentCN
                        this.translatedContentEN = translateData.translatedContentEN
                    }.also { wcdb.translate.insertObject(it) }
                    inserted++
                }
            }
            L.i { "[Translate] batch wrote updated=$updated inserted=$inserted requested=${dataByMessageId.size}" }
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

    
    
    
    fun selectableMessageCount(forWhat: For): Int {
        return wcdb.message.getValue(
            DBMessageModel.databaseId.count(),
            DBMessageModel.roomId.eq(forWhat.id)
                .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER, MessageModel.TYPE_UNSUPPORTED))
        )?.int ?: 0
    }

    override suspend fun updateMessageReadTime(conversationId: String, readMaxTimestamp: Long, readAt: Long) {
        val expression = DBMessageModel.roomId.eq(conversationId)
            .and(DBMessageModel.readTime.le(0L).or(DBMessageModel.readTime.isNull()))
            .and(DBMessageModel.systemShowTimestamp.le(readMaxTimestamp).or(DBMessageModel.systemShowTimestamp.eq(readMaxTimestamp)))
        
        
        
        wcdb.message.updateValue(
            readAt,
            DBMessageModel.readTime,
            expression
        )
        L.d { "[Message] updateMessageReadTime conversationId:${conversationId} readMaxTimestamp:${readMaxTimestamp} readAt:${readAt}" }
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


private const val SLOW_INSERT_MS = 100L
