package com.difft.android.messageserialization.db.store

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.appScope
import org.difft.app.database.delete
import org.difft.app.database.putMessageIfNotExists
import org.difft.app.database.putOrUpdateMessage
import org.difft.app.database.wcdb
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.mapToMessageId
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.base.WCDBException
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.rxCompletable
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.DBSpeechToTextModel
import org.difft.app.database.models.DBTranslateModel
import org.difft.app.database.models.PendingMessageModelNew
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.SpeechToTextModel
import org.difft.app.database.models.TranslateModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DBMessageStore
@Inject
constructor(
    private val dbRoomStore: DBRoomStore,
) : MessageStore {

    override fun putWhenNonExist(vararg messages: Message, useTransaction: Boolean) {
        L.d { "[Message] putWhenNonExist size:${messages.size} useTransaction:${useTransaction}" }
        val startTime = System.currentTimeMillis()
        try {
            if (!useTransaction) {
                messages.forEach {
                    dbRoomStore.createRoomIfNotExist(it.forWhat)
                    wcdb.putMessageIfNotExists(it)
                }
            } else {
                wcdb.db.runTransaction {
                    messages.forEach {
                        dbRoomStore.createRoomIfNotExist(it.forWhat)
                        wcdb.putMessageIfNotExists(it)
                    }
                    true
                }
            }
            // 只在事务成功后发送变更通知
            messages.groupBy { it.forWhat.id }.forEach { (roomId, _) ->
                RoomChangeTracker.trackRoom(roomId, RoomChangeType.MESSAGE)
            }
            val tookTime = System.currentTimeMillis() - startTime
            L.i { "[Message] putWhenNonExist batch took ${tookTime}ms for ${messages.size} messages" }
            if (tookTime > 5000) {
                FirebaseCrashlytics.getInstance().recordException(Exception("[putMessageException] putWhenNonExist took ${tookTime}ms for ${messages.size} messages"))
            }
        } catch (e: Exception) {
            L.e { "[Message] Failed to put messages: ${e.message}" }
            FirebaseCrashlytics.getInstance().recordException(Exception("[putMessageException] ${e.stackTraceToString()}"))
            throw e
        }
    }

    override fun putMessage(vararg messages: Message): Completable {
        return rxCompletable {
            val startTime = System.currentTimeMillis()
            try {
                wcdb.db.runTransaction {
                    messages.forEach {
                        dbRoomStore.createRoomIfNotExist(it.forWhat)
                        wcdb.putOrUpdateMessage(it)
                    }
                    true
                }
                // 只在事务成功后发送变更通知
                messages.groupBy { it.forWhat.id }.forEach { (roomId, _) ->
                    RoomChangeTracker.trackRoom(roomId, RoomChangeType.MESSAGE)
                }
                val tookTime = System.currentTimeMillis() - startTime
                L.i { "[Message] putMessage batch took ${tookTime}ms for ${messages.size} messages" }
                if (tookTime > 5000) {
                    FirebaseCrashlytics.getInstance().recordException(Exception("[putMessageException] putMessage took ${tookTime}ms for ${messages.size} messages"))
                }
            } catch (e: Exception) {
                L.e { "[Message] Failed to put messages: ${e.message}" }
                FirebaseCrashlytics.getInstance().recordException(Exception("[putMessageException] ${e.stackTraceToString()}"))
            }
        }
    }

    override fun deleteMessage(messageIds: List<String>) {
        appScope.launch {
            try {
                wcdb.message.getAllObjects(DBMessageModel.id.`in`(messageIds)).forEach {
                    it.delete()
                    RoomChangeTracker.trackRoom(it.roomId, RoomChangeType.MESSAGE)
                    L.d { "[Message] delete message success:" + it.id }
                }
            } catch (e: Exception) {
                L.e { "[Message] Failed to delete message: ${e.stackTraceToString()}" }
            }
        }
    }

    override fun removeRoomAndMessages(roomId: String) {
        appScope.launch {
            try {
                wcdb.room.deleteObjects(DBRoomModel.roomId.eq(roomId))
                wcdb.message.getAllObjects(DBMessageModel.roomId.eq(roomId)).forEach {
                    it.delete()
                }
                RoomChangeTracker.trackRoom(roomId, RoomChangeType.MESSAGE)
                L.i { "[Message] remove room and messages success: $roomId" }
            } catch (e: Exception) {
                L.e { "[Message] Failed to remove room and messages: ${e.stackTraceToString()}" }
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
    override fun updateMessageTranslateData(
        conversationId: String,
        messageId: String,
        translateData: TranslateData
    ): Completable {
        return Single.fromCallable {
            wcdb.message.getAllObjects(
                DBMessageModel.roomId.eq(conversationId)
                    .and(DBMessageModel.id.eq(messageId))
            )
        }.concatMapCompletable { models ->
            if (models.isNotEmpty()) {
                val oldMessage = models[0]
                val oldTranslateData = wcdb.translate.getFirstObject(
                    DBTranslateModel.messageId.eq(oldMessage.id)
                )
                if (oldTranslateData != null) {
                    oldTranslateData.translateStatus = translateData.translateStatus.status
                    oldTranslateData.translatedContentCN = translateData.translatedContentCN
                    oldTranslateData.translatedContentEN = translateData.translatedContentEN
                    // partial update existing translate row
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
                    // Insert brand new row
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
            Completable.complete()
        }
    }

    override fun updateMessageSpeechToTextData(
        conversationId: String,
        messageId: String,
        speechToTextData: SpeechToTextData
    ): Completable {
        return Single.fromCallable {
            wcdb.message.getAllObjects(
                DBMessageModel.roomId.eq(conversationId)
                    .and(DBMessageModel.id.eq(messageId))
            )
        }.concatMapCompletable { models ->
            if (models.isNotEmpty()) {
                val oldMessage = models[0]
                val oldSpeechToTextData = wcdb.speechToText.getFirstObject(
                    DBSpeechToTextModel.messageId.eq(oldMessage.id)
                )
                if (oldSpeechToTextData != null) {
                    oldSpeechToTextData.convertStatus = speechToTextData.convertStatus.status
                    oldSpeechToTextData.speechToTextContent = speechToTextData.speechToTextContent
                    // partial update existing speechToTex row
                    wcdb.speechToText.updateObject(
                        oldSpeechToTextData,
                        arrayOf(
                            DBSpeechToTextModel.convertStatus,
                            DBSpeechToTextModel.speechToTextContent,
                        ),
                        DBSpeechToTextModel.databaseId.eq(oldSpeechToTextData.databaseId)
                    )
                } else {
                    // Insert brand new row
                    SpeechToTextModel().apply {
                        this.messageId = oldMessage.id
                        this.convertStatus = speechToTextData.convertStatus.status
                        this.speechToTextContent = speechToTextData.speechToTextContent
                    }.also {
                        wcdb.speechToText.insertObject(it)
                    }
                }
            }
            Completable.complete()
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
                .and(DBMessageModel.type.notIn(2, 3))
        )?.int ?: 0
    }

    override fun updateMessageReadTime(conversationId: String, readMaxTimestamp: Long): Completable {
        return Completable.fromAction {
            val expression = DBMessageModel.roomId.eq(conversationId)
                .and(DBMessageModel.readTime.eq(0L).or(DBMessageModel.readTime.isNull()))
                .and(DBMessageModel.systemShowTimestamp.le(readMaxTimestamp).or(DBMessageModel.systemShowTimestamp.eq(readMaxTimestamp)))
            val list = wcdb.message.getAllObjects(expression)
            L.i { "[Message] updateMessageReadTime conversationId:${conversationId} readMaxTimestamp:${readMaxTimestamp} size:${list.size}" }
            if (list.isNotEmpty()) {
                wcdb.message.updateValue(
                    readMaxTimestamp,
                    DBMessageModel.readTime,
                    expression
                )
            }
        }
    }

    override fun updateSendStatus(message: Message, status: Int): Completable {
        return Completable.fromAction {
            val messageInDb = wcdb.message.getFirstObject(
                DBMessageModel.id.eq(message.id)
            )
            if (messageInDb == null) {
                message.sendType = status
                putMessage(message).blockingAwait()
            } else {
                wcdb.message.updateRow(
                    arrayOf(Value(message.systemShowTimestamp), Value(status)),
                    arrayOf(DBMessageModel.systemShowTimestamp, DBMessageModel.sendType),
                    DBMessageModel.id.eq(message.id)
                )
                RoomChangeTracker.trackRoom(message.forWhat.id, RoomChangeType.MESSAGE)
            }
        }
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