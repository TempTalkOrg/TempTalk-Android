package com.difft.android.chat.setting.archive


import android.annotation.SuppressLint
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.requests.ConversationShareRequestBody
import com.difft.android.network.requests.GetConversationShareRequestBody
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.JsonObject
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.Order
import difft.android.messageserialization.For
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.awaitFirst
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.delete
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBResetIdentityKeyModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.ResetIdentityKeyModel
import org.difft.app.database.wcdb
import util.AppForegroundObserver
import util.TimeUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class MessageArchiveManager @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val groupRepo: GroupRepo,
    private val globalConfigsManager: GlobalConfigsManager,
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
    private val dbMessageStore: DBMessageStore,
    private val localMessageCreator: dagger.Lazy<LocalMessageCreator>,
    private val conversationSettingsManager: dagger.Lazy<com.difft.android.chat.setting.ConversationSettingsManager>
) {
    companion object {
        private const val FOREGROUND_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
        private const val BACKGROUND_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
    }

    @Volatile
    private var currentInterval = FOREGROUND_INTERVAL_MS

    @Volatile
    private var lastCheckTime: Long = 0L

    // Channel to signal state changes and interrupt the delay
    private val stateChangeSignal = Channel<Unit>(Channel.CONFLATED)

    fun startCheckTask() {
        currentInterval = if (AppForegroundObserver.isForegrounded()) {
            FOREGROUND_INTERVAL_MS
        } else {
            BACKGROUND_INTERVAL_MS
        }

        appScope.launch(Dispatchers.IO) {
            checkIdentityKeyReset()

            while (true) {
                doArchiveIfNeeded()
                // Wait for either: interval timeout OR state change signal
                val signaled = withTimeoutOrNull(currentInterval) {
                    stateChangeSignal.receive()
                    true
                }
                if (signaled == true) {
                    L.i { "[MessageArchiveManager] State changed, interval now ${currentInterval / 1000}s" }
                }
            }
        }
    }

    /**
     * Call when app foreground/background state changes.
     * Updates the interval and triggers immediate check if needed.
     */
    fun onAppStateChanged(isForeground: Boolean) {
        currentInterval = if (isForeground) FOREGROUND_INTERVAL_MS else BACKGROUND_INTERVAL_MS
        stateChangeSignal.trySend(Unit)
    }

    private suspend fun doArchiveIfNeeded() {
        val timeSinceLastCheck = System.currentTimeMillis() - lastCheckTime

        // First call (lastCheckTime = 0) or exceeded interval
        if (lastCheckTime == 0L || timeSinceLastCheck >= currentInterval) {
            L.i { "[MessageArchiveManager] Archive check triggered (${timeSinceLastCheck / 1000}s since last)" }
            archiveMessages()
            lastCheckTime = System.currentTimeMillis()
        } else {
            L.d { "[MessageArchiveManager] Skip archive check, ${(currentInterval - timeSinceLastCheck) / 1000}s remaining" }
        }
    }

    /**
     * Archive expired messages for all rooms. A message is archived if either:
     * 1. readTime <= messageClearAnchor
     * 2. readTime + messageExpiry < currentTime
     *
     * Archive system messages ("Earlier messages expired") are skipped during deletion
     * to prevent accidentally emptying conversations. They are only replaced (delete + recreate)
     * when new normal messages are archived.
     */
    private suspend fun archiveMessages() {
        val pageSize = 100L
        val currentTimeMillis = System.currentTimeMillis()

        val roomsNeedCheckClear = wcdb.room.getAllObjects(
            DBRoomModel.roomId.notEq(globalServices.myId)
        )
        L.i { "[MessageArchiveManager] start archiving messages for ${roomsNeedCheckClear.size} rooms" }

        roomsNeedCheckClear.forEach { room ->
            try {
                var totalProcessedCount = 0

                val messageExpiryMillis = (room.messageExpiry ?: 0L) * 1000L
                val messageClearAnchor = room.messageClearAnchor ?: 0L
                val readPosition = room.readPosition

                val baseCondition = DBMessageModel.roomId.eq(room.roomId)

                val finalCondition = buildMessageClearCondition(
                    baseCondition = baseCondition,
                    messageClearAnchor = messageClearAnchor,
                    messageExpiryMillis = messageExpiryMillis,
                    currentTimeMillis = currentTimeMillis,
                    readPosition = readPosition
                ) ?: return@forEach // No expiry rules configured, skip this room

                while (true) {
                    val messagesToClear = wcdb.message.getAllObjects(
                        finalCondition,
                        null,
                        pageSize
                    )

                    if (messagesToClear.isNotEmpty()) {
                        messagesToClear.forEach { message ->
                            // Skip archive system messages to avoid emptying the conversation
                            if (!isArchiveExpiredSystemMessage(message)) {
                                totalProcessedCount++
                                message.delete()
                            }
                        }
                    }

                    if (messagesToClear.size < pageSize) {
                        break
                    }

                    delay(100)
                }

                // Replace the archive system message when normal messages were archived
                if (totalProcessedCount > 0) {
                    L.i { "[MessageArchiveManager] processed $totalProcessedCount normal messages for room ${room.roomId}" }

                    // Delete old archive system messages to avoid duplicates
                    wcdb.message.getAllObjects(
                        DBMessageModel.roomId.eq(room.roomId).and(DBMessageModel.type.eq(2))
                    ).forEach { message ->
                        if (isArchiveExpiredSystemMessage(message)) {
                            message.delete()
                        }
                    }

                    // Anchor the new archive message to the earliest remaining message
                    val earliestMessage = wcdb.message.getFirstObject(
                        DBMessageModel.roomId.eq(room.roomId),
                        DBMessageModel.systemShowTimestamp.order(Order.Asc)
                    )

                    // Place it 1ms before the earliest message so it appears first
                    val systemShowTimestamp = earliestMessage?.systemShowTimestamp?.minus(1)
                        ?: System.currentTimeMillis()

                    // Use the earliest message's readTime for consistent expiry calculation.
                    // Fallback to current time if readTime = 0 (unread) to ensure it can be archived later.
                    val readTime = earliestMessage?.readTime?.takeIf { it > 0 } ?: System.currentTimeMillis()

                    // Inherit expiresInSeconds from earliest message, or fall back to room-level messageExpiry
                    val expiresInSeconds = earliestMessage?.expiresInSeconds ?: (room.messageExpiry ?: 0L).toInt()

                    L.i { "[MessageArchiveManager] creating archive message for room ${room.roomId}, timestamp: $systemShowTimestamp, readTime: $readTime, expiresInSeconds: $expiresInSeconds" }
                    localMessageCreator.get().createEarlierMessagesExpiredMessage(
                        room.roomId,
                        room.roomType,
                        systemShowTimestamp,
                        readTime,
                        expiresInSeconds
                    ).let { message ->
                        wcdb.message.insertObject(message)
                    }
                }
            } catch (e: Exception) {
                L.e { "[MessageArchiveManager] error archiving messages for room:${room.roomId} error:${e.stackTraceToString()}" }
            }
        }

        L.i { "[MessageArchiveManager] finished archiving messages" }
    }

    /**
     * Build the WHERE condition for expired message deletion.
     *
     * Normal messages (readTime > 0):
     * - readTime <= messageClearAnchor, OR
     * - readTime + messageExpiry < currentTime
     *
     * Legacy messages (readTime = 0, fallback):
     * - When messageExpiry > 0 and systemShowTimestamp <= readPosition,
     *   use systemShowTimestamp instead of readTime for expiry calculation.
     *
     * @return null if no expiry rules apply (neither messageClearAnchor nor messageExpiry is set)
     */
    private fun buildMessageClearCondition(
        baseCondition: Expression,
        messageClearAnchor: Long,
        messageExpiryMillis: Long,
        currentTimeMillis: Long,
        readPosition: Long
    ): Expression? {
        // Legacy fallback: messages with readTime = 0 but systemShowTimestamp <= readPosition.
        // Only enabled when both messageExpiry and readPosition are positive.
        val legacyFallback = if (messageExpiryMillis > 0 && readPosition > 0) {
            DBMessageModel.readTime.eq(0)
                .and(DBMessageModel.systemShowTimestamp.le(readPosition))
                .and(DBMessageModel.systemShowTimestamp.add(messageExpiryMillis).lt(currentTimeMillis))
        } else null

        return when {
            // Both messageClearAnchor and messageExpiry: check either condition
            messageClearAnchor > 0 && messageExpiryMillis > 0 -> {
                val hasReadTime = DBMessageModel.readTime.gt(0)
                val clearAnchorCondition = DBMessageModel.readTime.le(messageClearAnchor)
                val expiryCondition = DBMessageModel.readTime.add(messageExpiryMillis).lt(currentTimeMillis)
                val normalCondition = hasReadTime.and(clearAnchorCondition.or(expiryCondition))

                if (legacyFallback != null) {
                    baseCondition.and(normalCondition.or(legacyFallback))
                } else {
                    baseCondition.and(normalCondition)
                }
            }
            // Only messageClearAnchor: no legacy fallback needed (clearAnchor is an explicit cutoff)
            messageClearAnchor > 0 -> {
                baseCondition.and(DBMessageModel.readTime.gt(0)).and(DBMessageModel.readTime.le(messageClearAnchor))
            }
            // Only messageExpiry: check time-based expiry
            messageExpiryMillis > 0 -> {
                val hasReadTime = DBMessageModel.readTime.gt(0)
                val expiryCondition = DBMessageModel.readTime.add(messageExpiryMillis).lt(currentTimeMillis)
                val normalCondition = hasReadTime.and(expiryCondition)

                if (legacyFallback != null) {
                    baseCondition.and(normalCondition.or(legacyFallback))
                } else {
                    baseCondition.and(normalCondition)
                }
            }
            // Neither set: no messages to delete
            else -> null
        }
    }

    /**
     * Check if a message is the "Earlier messages expired" archive system message.
     *
     * Fast path: non-Notify messages (type != 2) return immediately without JSON parsing.
     * Slow path: only Notify messages require JSON parsing, which are typically very few.
     */
    private fun isArchiveExpiredSystemMessage(message: MessageModel): Boolean {
        if (message.type != 2) return false
        return try {
            val json = globalServices.gson.fromJson(message.messageText, JsonObject::class.java)
            val data = json?.get("data")?.asJsonObject
            val actionType = data?.get("actionType")?.asInt
            actionType == TTNotifyMessage.NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED
        } catch (e: Exception) {
            false
        }
    }

    fun getDefaultMessageArchiveTime(): Long {
        return globalConfigsManager.getNewGlobalConfigs()?.data?.disappearanceTimeInterval?.message?.default ?: 0L
    }

    fun getDefaultArchiveTimeList(): List<Long> {
        return globalConfigsManager.getNewGlobalConfigs()?.data?.disappearanceTimeInterval?.messageArchivingTimeOptionValues ?: emptyList()
    }

    fun getGroupDefaultArchiveTimeList(): List<Long> {
        return globalConfigsManager.getNewGlobalConfigs()?.data?.group?.messageArchivingTimeOptionValues ?: emptyList()
    }

    fun conversationParams(targetID: String): String {
        val myId = globalServices.myId
        return if (globalServices.myId < targetID) "$myId:$targetID" else "$targetID:$myId"
    }

    fun getMessageArchiveTime(forWhat: For, force: Boolean = false): Single<Long> {
        if (globalServices.myId == forWhat.id) {
            return Single.just(0L)
        }
        return if (force) {
            getMessageArchiveTimeFromServer(forWhat)
        } else {
            dbRoomStore.getMessageExpiry(forWhat).flatMap { time ->
                if (time.isPresent) {
                    Single.just(time.get())
                } else {
                    getMessageArchiveTimeFromServer(forWhat)
                }
            }
        }.subscribeOn(Schedulers.io())
    }

    private fun getMessageArchiveTimeFromServer(forWhat: For): Single<Long> = if (forWhat is For.Group) {
        groupRepo.getGroupInfo(forWhat.id)
            .map { response ->
                if (response.isSuccess()) {
                    val messageExpiry = response.data?.messageExpiry?.toLong()?.takeIf { it >= 0L } ?: getDefaultMessageArchiveTime()
                    val messageClearAnchor = response.data?.messageClearAnchor ?: 0L
                    dbRoomStore.updateMessageExpiry(forWhat, messageExpiry, messageClearAnchor).blockingAwait()
                    conversationSettingsManager.get().emitConversationSettingUpdate(
                        conversationId = forWhat.id,
                        messageExpiry = messageExpiry,
                        messageClearAnchor = messageClearAnchor
                    )
                    L.i { "[MessageArchiveManager] getMessageArchiveTime success:" + forWhat.id + "====" + messageExpiry }
                    messageExpiry
                } else {
                    L.i { "[MessageArchiveManager] getMessageArchiveTime fail:" + forWhat.id + "====" + response.reason }
                    getDefaultMessageArchiveTime()
                }
            }
    } else {
        chatHttpClient.httpService
            .fetchShareConversationConfig(
                SecureSharedPrefsUtil.getToken(),
                GetConversationShareRequestBody(
                    listOf(conversationParams(forWhat.id)),
                    false
                )
            )
            .map { response ->
                if (response.isSuccess()) {
                    val messageExpiry = response.data?.conversations?.firstOrNull()?.messageExpiry?.takeIf { it >= 0L } ?: getDefaultMessageArchiveTime()
                    val messageClearAnchor = response.data?.conversations?.firstOrNull()?.messageClearAnchor ?: 0L
                    dbRoomStore.updateMessageExpiry(forWhat, messageExpiry, messageClearAnchor).blockingAwait()
                    conversationSettingsManager.get().emitConversationSettingUpdate(
                        conversationId = forWhat.id,
                        messageExpiry = messageExpiry,
                        messageClearAnchor = messageClearAnchor
                    )
                    L.i { "[MessageArchiveManager] getMessageArchiveTime success:" + forWhat.id + "====" + messageExpiry }
                    messageExpiry
                } else {
                    L.i { "[MessageArchiveManager] getMessageArchiveTime fail:" + forWhat.id + "====" + response.reason }
                    getDefaultMessageArchiveTime()
                }
            }
    }

    fun updateMessageArchiveTime(forWhat: For, messageExpiry: Long): Completable {
        return if (forWhat is For.Group) {
            groupRepo.changeGroupSettings(forWhat.id, ChangeGroupSettingsReq(messageExpiry = messageExpiry))
                .concatMapCompletable { response ->
                    if (response.status == 0) {
                        val messageClearAnchor = response.data?.messageClearAnchor ?: 0L
                        conversationSettingsManager.get().emitConversationSettingUpdate(
                            conversationId = forWhat.id,
                            messageExpiry = messageExpiry,
                            messageClearAnchor = messageClearAnchor
                        )
                        Completable.complete()
                    } else {
                        Completable.error(NetworkException(message = response.reason ?: ""))
                    }
                }
        } else {
            chatHttpClient.httpService
                .updateConversationConfig(
                    SecureSharedPrefsUtil.getToken(),
                    conversationParams(forWhat.id),
                    ConversationShareRequestBody(messageExpiry)
                )
                .concatMapCompletable { response ->
                    if (response.status == 0) {
                        // DM API returns messageClearAnchor
                        val messageClearAnchor = response.data?.messageClearAnchor ?: 0L
                        conversationSettingsManager.get().emitConversationSettingUpdate(
                            conversationId = forWhat.id,
                            messageExpiry = messageExpiry,
                            messageClearAnchor = messageClearAnchor
                        )
                        Completable.complete()
                    } else {
                        Completable.error(NetworkException(message = response.reason ?: ""))
                    }
                }
        }
    }

    /**
     * Update local message expiry. Called by MessageContentProcessor when handling Type 5 notify.
     * Note: Event notification is emitted by the caller (MessageContentProcessor), not here.
     */
    @SuppressLint("CheckResult")
    fun updateLocalArchiveTime(forWhat: For, messageExpiry: Long, messageClearAnchor: Long) {
        dbRoomStore.updateMessageExpiry(forWhat, messageExpiry, messageClearAnchor)
            .compose(RxUtil.getCompletableTransformer())
            .subscribe({
                // Event notification is emitted by MessageContentProcessor
            }, { L.e { "[MessageArchiveManager] updateLocalArchiveTime error: ${it.stackTraceToString()}" } })
    }

    /**
     * Delete all messages sent by [userId] with timestamp before [timestamp], in batches.
     */
    private suspend fun clearMessagesBeforeTimestamp(userId: String, timestamp: Long, pageSize: Long = 100) {
        try {
            L.i { "[MessageArchiveManager] start clearing messages for user: $userId before timestamp: $timestamp" }
            while (true) {
                val messages = wcdb.message.getAllObjects(
                    DBMessageModel.fromWho.eq(userId)
                        .and(DBMessageModel.type.notEq(2))
                        .and(DBMessageModel.timeStamp.lt(timestamp))
                        .and(DBMessageModel.roomId.notEq(globalServices.myId)),
                    null,
                    pageSize
                )

                L.i { "[MessageArchiveManager] clearMessagesBeforeTimestamp:" + messages.size + "===" + messages.map { it.timeStamp } }
                messages.forEach {
                    it.delete()
                }

                if (messages.size < pageSize) {
                    break
                }

                delay(100)
            }

            L.i { "[MessageArchiveManager] finished clearing messages for user: $userId before timestamp: $timestamp" }
        } catch (e: Exception) {
            L.e { "[MessageArchiveManager] error clearing messages: ${e.message}" }
        }
    }

    /** Check for users who reset their identity key and clear their corresponding messages. */
    private suspend fun checkIdentityKeyReset() {
        try {
            // Fetch incremental reset records starting from the latest known reset timestamp
            val maxResetTime = wcdb.resetIdentityKey.getValue(DBResetIdentityKeyModel.resetTime.max())?.long ?: 0L
            L.i { "[MessageArchiveManager] checkIdentityKeyReset maxResetTime: $maxResetTime" }

            val checkIdentityKeyResetResponse = chatHttpClient.httpService.getPublicKeys(
                SecureSharedPrefsUtil.getToken(),
                GetPublicKeysReq(beginTimestamp = maxResetTime)
            ).awaitFirst()
            if (checkIdentityKeyResetResponse.isSuccess()) {
                val resetIdentityKeyModels = checkIdentityKeyResetResponse.data?.keys?.map {
                    ResetIdentityKeyModel().apply {
                        uid = it.uid
                        resetTime = it.resetIdentityKeyTime
                        status = 0
                    }
                }
                L.i { "[MessageArchiveManager] checkIdentityKeyReset success: ${resetIdentityKeyModels?.map { it.uid to it.resetTime }}" }
                if (!resetIdentityKeyModels.isNullOrEmpty()) {
                    wcdb.resetIdentityKey.insertOrReplaceObjects(resetIdentityKeyModels)
                    wcdb.resetIdentityKey.getAllObjects(DBResetIdentityKeyModel.status.eq(0))
                        .map { resetIdentityKeyModel ->
                            archiveMessagesByResetIdentityKey(resetIdentityKeyModel.uid, resetIdentityKeyModel.resetTime)
                        }
                }
            } else {
                L.w { "[MessageArchiveManager] checkIdentityKeyReset failed: ${checkIdentityKeyResetResponse.reason}" }
            }
        } catch (e: Exception) {
            L.e { "[MessageArchiveManager] checkIdentityKeyReset failed -> ${e.stackTraceToString()}" }
        }
    }

    suspend fun archiveMessagesByResetIdentityKey(operator: String, resetIdentityKeyTime: Long) {
        if (operator == globalServices.myId) { // Self reset: create notify messages for all 1v1 conversations
            wcdb.room.getAllObjects(DBRoomModel.roomId.notEq(globalServices.myId).and(DBRoomModel.roomType.eq(0)))
                .map { room ->
                    localMessageCreator.get().createResetIdentityKeyMessage(operator, For.Account(room.roomId), resetIdentityKeyTime, room.messageExpiry)
                }.let { messages ->
                    L.i { "[MessageArchiveManager] create reset identity key notify message for self, rooms size:${messages.size}" }
                    dbMessageStore.putWhenNonExist(*messages.toTypedArray())
                }
        } else { // Other user reset: create notify message for the 1v1 conversation if it exists
            wcdb.room.getFirstObject(DBRoomModel.roomId.eq(operator))?.let {
                L.i { "[MessageArchiveManager] create reset identity key message -> operator:${operator}  resetIdentityKeyTime:${resetIdentityKeyTime}" }
                localMessageCreator.get().createResetIdentityKeyMessage(operator, For.Account(operator), resetIdentityKeyTime, it.messageExpiry).let { message ->
                    dbMessageStore.putWhenNonExist(message)
                }
            } ?: run {
                L.i { "[MessageArchiveManager] no need to create reset identity key message, can't find room for operator:${operator}" }
            }
        }
        clearMessagesBeforeTimestamp(operator, resetIdentityKeyTime)

        wcdb.resetIdentityKey.insertOrReplaceObject(ResetIdentityKeyModel().apply {
            uid = operator
            resetTime = resetIdentityKeyTime
            status = 1 // Mark as processed
        })
    }
}

fun Long.toArchiveTimeDisplayText(): String {
    return if (this == 0L) {
        ResUtils.getString(R.string.disappearing_messages_not_archive)
    } else {
        TimeUtils.millis2FitTimeSpan(this.seconds.inWholeMilliseconds, 3, true)
    }
}