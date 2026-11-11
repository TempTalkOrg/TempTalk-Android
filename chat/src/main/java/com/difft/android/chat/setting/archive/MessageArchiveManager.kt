package com.difft.android.chat.setting.archive


import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.R
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.ServerTimeSyncer
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.requests.ConversationShareRequestBody
import com.difft.android.network.requests.GetConversationShareRequestBody
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.Order
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.delete
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBResetIdentityKeyModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.ResetIdentityKeyModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.wcdb
import util.AppForegroundObserver
import util.TimeUtils
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds


@Suppress("BlockingWcdbInSuspend")
@Singleton
class MessageArchiveManager @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val groupRepo: GroupRepo,
    private val globalConfigsManager: GlobalConfigsManager,
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
    private val dbMessageStore: DBMessageStore,
    private val localMessageCreator: dagger.Lazy<LocalMessageCreator>,
    private val conversationSettingsManager: dagger.Lazy<com.difft.android.chat.setting.ConversationSettingsManager>,
    private val gson: Gson,
    private val serverTimeSyncer: ServerTimeSyncer,
) {
    companion object {
        private const val FOREGROUND_INTERVAL_MS = 5 * 60 * 1000L  
        private const val BACKGROUND_INTERVAL_MS = 60 * 60 * 1000L 

        
        private const val RESET_STATUS_PENDING = 0

        
        private const val RESET_STATUS_PROCESSED = 1

        




        internal fun pendingResetIdentityKeyRecords(
            known: Map<String?, ResetIdentityKeyModel>,
            remote: List<PublicKeyInfo>,
        ): List<ResetIdentityKeyModel> = remote.filter { info ->
            val local = known[info.uid]
            local == null || local.status != RESET_STATUS_PROCESSED || local.resetTime != info.resetIdentityKeyTime
        }.map { info ->
            ResetIdentityKeyModel().apply {
                uid = info.uid
                resetTime = info.resetIdentityKeyTime
                status = RESET_STATUS_PENDING
            }
        }
    }

    @Volatile
    private var currentInterval = FOREGROUND_INTERVAL_MS

    @Volatile
    private var lastCheckTime: Long = 0L

    
    private val isStarted = AtomicBoolean(false)

    
    private val archiveMutex = Mutex()

    
    private val stateChangeSignal = Channel<Unit>(Channel.CONFLATED)

    
    private val keyUnavailableLogged = AtomicBoolean(false)

    
    @VisibleForTesting
    internal var isDbInaccessible: () -> Boolean = { wcdb.isDbInaccessible }

    @VisibleForTesting
    internal var elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }

    @VisibleForTesting
    internal var archivePass: suspend () -> Unit = { archiveMessages() }

    @VisibleForTesting
    internal val lastCheckTimeForTest: Long get() = lastCheckTime

    



    fun startCheckTask(): Boolean {
        if (!isStarted.compareAndSet(false, true)) return false

        currentInterval = if (AppForegroundObserver.isForegrounded()) {
            FOREGROUND_INTERVAL_MS
        } else {
            BACKGROUND_INTERVAL_MS
        }

        appScope.launch(Dispatchers.IO) {
            
            
            if (isDbInaccessible()) {
                L.w { "[MessageArchiveManager] skip check task, DB inaccessible" }
                return@launch
            }

            checkIdentityKeyReset()

            runArchiveLoop()
        }
        return true
    }

    @VisibleForTesting
    internal suspend fun runArchiveLoop() {
        while (true) {
            try {
                doArchiveIfNeeded()
            } catch (e: WCDBKeyUnavailableException) {
                
                
                if (keyUnavailableLogged.compareAndSet(false, true)) {
                    L.w { "[MessageArchiveManager] archive round skipped error=${e.javaClass.simpleName}" }
                }
            }
            
            val signaled = withTimeoutOrNull(currentInterval) {
                stateChangeSignal.receive()
                true
            }
            if (signaled == true) {
                L.i { "[MessageArchiveManager] State changed, interval now ${currentInterval / 1000}s" }
            }
        }
    }

    



    fun onAppStateChanged(isForeground: Boolean) {
        currentInterval = if (isForeground) FOREGROUND_INTERVAL_MS else BACKGROUND_INTERVAL_MS
        stateChangeSignal.trySend(Unit)
    }

    private suspend fun doArchiveIfNeeded() = archiveMutex.withLock {
        
        if (isDbInaccessible()) return@withLock
        
        
        val timeSinceLastCheck = elapsedRealtime() - lastCheckTime

        
        if (lastCheckTime == 0L || timeSinceLastCheck >= currentInterval) {
            L.i { "[MessageArchiveManager] Archive check triggered (${timeSinceLastCheck / 1000}s since last)" }
            archivePass()
            lastCheckTime = elapsedRealtime()
        } else {
            L.d { "[MessageArchiveManager] Skip archive check, ${(currentInterval - timeSinceLastCheck) / 1000}s remaining" }
        }
    }

    








    private suspend fun archiveMessages() {
        
        serverTimeSyncer.ensureAnchored()

        val pageSize = 100L
        
        val currentTimeMillis = ServerTimeProvider.nowMillis()

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
                ) ?: return@forEach 

                while (true) {
                    val messagesToClear = wcdb.message.getAllObjects(
                        finalCondition,
                        null,
                        pageSize
                    )

                    if (messagesToClear.isNotEmpty()) {
                        messagesToClear.forEach { message ->
                            
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

                
                if (totalProcessedCount > 0) {
                    L.i { "[MessageArchiveManager] processed $totalProcessedCount normal messages for room ${room.roomId}" }

                    
                    val existingTombstone = wcdb.message.getAllObjects(
                        DBMessageModel.roomId.eq(room.roomId).and(DBMessageModel.type.eq(MessageModel.TYPE_NOTIFY))
                    ).firstOrNull { isArchiveExpiredSystemMessage(it) }

                    if (existingTombstone != null) {
                        
                        if (existingTombstone.systemShowTimestamp != MessageModel.ARCHIVE_TOMBSTONE_SORT_SENTINEL) {
                            wcdb.message.updateValue(
                                MessageModel.ARCHIVE_TOMBSTONE_SORT_SENTINEL,
                                DBMessageModel.systemShowTimestamp,
                                DBMessageModel.id.eq(existingTombstone.id)
                            )
                        }
                    } else {
                        
                        val earliestMessage = wcdb.message.getFirstObject(
                            DBMessageModel.roomId.eq(room.roomId),
                            DBMessageModel.systemShowTimestamp.order(Order.Asc)
                        )

                        val systemShowTimestamp = MessageModel.ARCHIVE_TOMBSTONE_SORT_SENTINEL

                        
                        
                        val readTime = earliestMessage?.readTime?.takeIf { it > 0 } ?: ServerTimeProvider.nowMillis()

                        
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
                }
            } catch (e: Exception) {
                L.e { "[MessageArchiveManager] error archiving messages for room:${room.roomId} error:${e.stackTraceToString()}" }
            }
        }

        L.i { "[MessageArchiveManager] finished archiving messages" }
    }

    

















    private fun buildMessageClearCondition(
        baseCondition: Expression,
        messageClearAnchor: Long,
        messageExpiryMillis: Long,
        currentTimeMillis: Long,
        readPosition: Long
    ): Expression? {
        
        
        val legacyFallback = if (messageExpiryMillis > 0 && readPosition > 0) {
            DBMessageModel.readTime.eq(0)
                .and(DBMessageModel.systemShowTimestamp.le(readPosition))
                .and(DBMessageModel.systemShowTimestamp.add(messageExpiryMillis).lt(currentTimeMillis))
        } else null

        return when {
            
            messageClearAnchor > 0 && messageExpiryMillis > 0 -> {
                val hasReadTime = DBMessageModel.readTime.gt(0)
                
                val clearAnchorCondition = DBMessageModel.readTime.le(messageClearAnchor)
                    .and(DBMessageModel.systemShowTimestamp.le(messageClearAnchor))
                val expiryCondition = DBMessageModel.readTime.add(messageExpiryMillis).lt(currentTimeMillis)
                    .and(DBMessageModel.systemShowTimestamp.add(messageExpiryMillis).lt(currentTimeMillis))
                val normalCondition = hasReadTime.and(clearAnchorCondition.or(expiryCondition))

                if (legacyFallback != null) {
                    baseCondition.and(normalCondition.or(legacyFallback))
                } else {
                    baseCondition.and(normalCondition)
                }
            }
            
            messageClearAnchor > 0 -> {
                
                baseCondition.and(DBMessageModel.readTime.gt(0))
                    .and(DBMessageModel.readTime.le(messageClearAnchor))
                    .and(DBMessageModel.systemShowTimestamp.le(messageClearAnchor))
            }
            
            messageExpiryMillis > 0 -> {
                val hasReadTime = DBMessageModel.readTime.gt(0)
                
                val expiryCondition = DBMessageModel.readTime.add(messageExpiryMillis).lt(currentTimeMillis)
                    .and(DBMessageModel.systemShowTimestamp.add(messageExpiryMillis).lt(currentTimeMillis))
                val normalCondition = hasReadTime.and(expiryCondition)

                if (legacyFallback != null) {
                    baseCondition.and(normalCondition.or(legacyFallback))
                } else {
                    baseCondition.and(normalCondition)
                }
            }
            
            else -> null
        }
    }

    





    private fun isArchiveExpiredSystemMessage(message: MessageModel): Boolean {
        if (message.type != 2) return false
        return try {
            val json = gson.fromJson(message.messageText, JsonObject::class.java)
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

    suspend fun getMessageArchiveTime(forWhat: For, force: Boolean = false): Long {
        if (globalServices.myId == forWhat.id) {
            return 0L
        }
        return if (force) {
            getMessageArchiveTimeFromServer(forWhat)
        } else {
            val time = dbRoomStore.getMessageExpiry(forWhat)
            if (time.isPresent) {
                time.get()
            } else {
                getMessageArchiveTimeFromServer(forWhat)
            }
        }
    }

    private suspend fun getMessageArchiveTimeFromServer(forWhat: For): Long = if (forWhat is For.Group) {
        val response = groupRepo.getGroupInfo(forWhat.id)
        if (response.isSuccess()) {
            val messageExpiry = response.data?.messageExpiry?.toLong()?.takeIf { it >= 0L } ?: getDefaultMessageArchiveTime()
            val messageClearAnchor = response.data?.messageClearAnchor ?: 0L
            dbRoomStore.updateMessageExpiry(forWhat, messageExpiry, messageClearAnchor)
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
    } else {
        val response = chatHttpClient.httpService
            .fetchShareConversationConfig(
                GetConversationShareRequestBody(
                    listOf(conversationParams(forWhat.id)),
                    false
                )
            )
        if (response.isSuccess()) {
            val messageExpiry = response.data?.conversations?.firstOrNull()?.messageExpiry?.takeIf { it >= 0L } ?: getDefaultMessageArchiveTime()
            val messageClearAnchor = response.data?.conversations?.firstOrNull()?.messageClearAnchor ?: 0L
            dbRoomStore.updateMessageExpiry(forWhat, messageExpiry, messageClearAnchor)
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

    suspend fun updateMessageArchiveTime(forWhat: For, messageExpiry: Long) {
        if (forWhat is For.Group) {
            val response = groupRepo.changeGroupSettings(forWhat.id, ChangeGroupSettingsReq(messageExpiry = messageExpiry))
            if (response.status == 0) {
                val messageClearAnchor = response.data?.messageClearAnchor ?: 0L
                conversationSettingsManager.get().emitConversationSettingUpdate(
                    conversationId = forWhat.id,
                    messageExpiry = messageExpiry,
                    messageClearAnchor = messageClearAnchor
                )
            } else {
                throw NetworkException(message = response.reason ?: "")
            }
        } else {
            val response = chatHttpClient.httpService
                .updateConversationConfig(
                    conversationParams(forWhat.id),
                    ConversationShareRequestBody(messageExpiry)
                )
            if (response.status == 0) {
                val messageClearAnchor = response.data?.messageClearAnchor ?: 0L
                conversationSettingsManager.get().emitConversationSettingUpdate(
                    conversationId = forWhat.id,
                    messageExpiry = messageExpiry,
                    messageClearAnchor = messageClearAnchor
                )
            } else {
                throw NetworkException(message = response.reason ?: "")
            }
        }
    }

    






    fun updateLocalArchiveTime(forWhat: For, messageExpiry: Long, messageClearAnchor: Long, admission: MessagingTicket?) {
        val held = admission?.retain("archive-time")
        appScope.launch(Dispatchers.IO) {
            try {
                dbRoomStore.updateMessageExpiry(forWhat, messageExpiry, messageClearAnchor)
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[MessageArchiveManager] updateLocalArchiveTime error: ${e.stackTraceToString()}" }
            }
        }.invokeOnCompletion { held?.close() } 
    }

    
    private fun resetIdentityKeyClearCondition(userId: String, timestamp: Long): Expression =
        DBMessageModel.fromWho.eq(userId)
            .and(DBMessageModel.type.notEq(MessageModel.TYPE_NOTIFY))
            .and(DBMessageModel.timeStamp.lt(timestamp))
            .and(DBMessageModel.roomId.notEq(globalServices.myId))

    



    private suspend fun clearMessagesBeforeTimestamp(userId: String, timestamp: Long, pageSize: Long = 100): Boolean {
        return try {
            L.i { "[MessageArchiveManager] start clearing messages for user: $userId before timestamp: $timestamp" }
            while (true) {
                val messages = wcdb.message.getAllObjects(resetIdentityKeyClearCondition(userId, timestamp), null, pageSize)

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
            true
        } catch (e: Exception) {
            L.e { "[MessageArchiveManager] error clearing messages: ${e.message}" }
            false
        }
    }

    






    private suspend fun checkIdentityKeyReset() {
        try {
            
            val maxResetTime = wcdb.resetIdentityKey.getValue(DBResetIdentityKeyModel.resetTime.max())?.long ?: 0L
            L.i { "[MessageArchiveManager] checkIdentityKeyReset maxResetTime: $maxResetTime" }

            val checkIdentityKeyResetResponse = chatHttpClient.httpService.getPublicKeys(
                GetPublicKeysReq(beginTimestamp = maxResetTime)
            )
            if (checkIdentityKeyResetResponse.isSuccess()) {
                val remote = checkIdentityKeyResetResponse.data?.keys.orEmpty()
                val known = wcdb.resetIdentityKey.getAllObjects().associateBy { it.uid }
                val pending = pendingResetIdentityKeyRecords(known, remote)
                L.i { "[MessageArchiveManager] checkIdentityKeyReset remote=${remote.size} pending=${pending.map { it.uid to it.resetTime }}" }
                if (pending.isNotEmpty()) {
                    wcdb.resetIdentityKey.insertOrReplaceObjects(pending)
                }
                
                wcdb.resetIdentityKey.getAllObjects(DBResetIdentityKeyModel.status.eq(RESET_STATUS_PENDING))
                    .forEach { resetIdentityKeyModel ->
                        archiveMessagesByResetIdentityKey(resetIdentityKeyModel.uid!!, resetIdentityKeyModel.resetTime!!)
                    }
            } else {
                L.w { "[MessageArchiveManager] checkIdentityKeyReset failed: ${checkIdentityKeyResetResponse.reason}" }
            }
        } catch (e: Exception) {
            L.e { "[MessageArchiveManager] checkIdentityKeyReset failed -> ${e.stackTraceToString()}" }
        }
    }

    



















    suspend fun archiveMessagesByResetIdentityKey(operator: String, resetIdentityKeyTime: Long) = archiveMutex.withLock {
        val noticeRooms = roomsNeedingResetIdentityKeyNotice(operator, resetIdentityKeyTime)
        if (noticeRooms.isNotEmpty()) {
            val messages = noticeRooms.map { room ->
                localMessageCreator.get().createResetIdentityKeyMessage(operator, For.Account(room.roomId), resetIdentityKeyTime, room.messageExpiry ?: 0L)
            }
            dbMessageStore.putWhenNonExist(*messages.toTypedArray())
        }

        val cleared = clearMessagesBeforeTimestamp(operator, resetIdentityKeyTime)
        L.i { "[MessageArchiveManager] reset identity key archived operator=$operator resetTime=$resetIdentityKeyTime noticeRooms=${noticeRooms.size} cleared=$cleared" }

        wcdb.resetIdentityKey.insertOrReplaceObject(ResetIdentityKeyModel().apply {
            uid = operator
            resetTime = resetIdentityKeyTime
            status = if (cleared) RESET_STATUS_PROCESSED else RESET_STATUS_PENDING
        })
    }

    
    private fun roomsNeedingResetIdentityKeyNotice(operator: String, resetIdentityKeyTime: Long): List<RoomModel> {
        val myId = globalServices.myId
        val candidates = if (operator == myId) {
            wcdb.room.getAllObjects(DBRoomModel.roomId.notEq(myId).and(DBRoomModel.roomType.eq(0)))
        } else {
            listOfNotNull(wcdb.room.getFirstObject(DBRoomModel.roomId.eq(operator)))
        }
        return candidates.filter { room ->
            val hasMessagesToClear = wcdb.message.getFirstObject(
                resetIdentityKeyClearCondition(operator, resetIdentityKeyTime).and(DBMessageModel.roomId.eq(room.roomId))
            ) != null
            val hasNotice = wcdb.message.getFirstObject(
                DBMessageModel.roomId.eq(room.roomId)
                    .and(DBMessageModel.type.eq(MessageModel.TYPE_NOTIFY))
                    .and(DBMessageModel.systemShowTimestamp.eq(resetIdentityKeyTime))
            ) != null
            hasMessagesToClear && !hasNotice
        }
    }
}

fun Long.toArchiveTimeDisplayText(): String {
    return if (this == 0L) {
        ResUtils.getString(R.string.disappearing_messages_not_archive)
    } else {
        TimeUtils.millis2FitTimeSpan(this.seconds.inWholeMilliseconds, 3, true)
    }
}