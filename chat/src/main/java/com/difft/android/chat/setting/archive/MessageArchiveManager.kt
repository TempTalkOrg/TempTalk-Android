package com.difft.android.chat.setting.archive


import android.annotation.SuppressLint
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import org.difft.app.database.delete
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.contacts.data.ContactorUtil
import difft.android.messageserialization.For
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
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.Order
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBResetIdentityKeyModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.ResetIdentityKeyModel
import util.TimeUtils
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class MessageArchiveManager @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val groupRepo: GroupRepo,
    private val globalConfigsManager: GlobalConfigsManager,
    @ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
    private val dbMessageStore: DBMessageStore
) {
    fun startCheckTask() {
        appScope.launch(Dispatchers.IO) {
            delay(3000L) // 延迟3秒开始执行
            FileUtil.deleteMessageAttachmentEmptyDirectories()

            checkIdentityKeyReset()

            while (true) {
                archiveMessages()
                delay(5 * 60 * 1000L) // 5 minutes delay
            }
        }
    }

    /**
     * 消息过期基于会话设置，满足两个条件之一便会归档销毁
     * 1. 消息的readTime小于等于messageClearAnchor
     * 2. 消息的readTime加上expiresInSeconds小于当前时间
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

                // 构建查询条件
                val baseCondition = DBMessageModel.roomId.eq(room.roomId)
                    .and(DBMessageModel.readTime.gt(0))

                val finalCondition = buildMessageClearCondition(
                    baseCondition = baseCondition,
                    messageClearAnchor = messageClearAnchor,
                    messageExpiryMillis = messageExpiryMillis,
                    currentTimeMillis = currentTimeMillis
                )

                // 如果没有需要清除的消息，跳过这个房间
                if (finalCondition == null) {
                    return@forEach
                }

                while (true) {
                    val messagesToClear = wcdb.message.getAllObjects(
                        finalCondition,
                        null,
                        pageSize
                    )

                    if (messagesToClear.isNotEmpty()) {
                        totalProcessedCount += messagesToClear.size
                        messagesToClear.forEach {
                            it.delete()
                        }
                    }

                    // 如果查询结果数量小于pageSize，说明已经是最后一批数据，不需要继续查询
                    if (messagesToClear.size < pageSize) {
                        break
                    }

                    delay(100)
                }

                // 如果该会话有消息被清除，创建一条Earlier messages expired的系统消息
                if (totalProcessedCount > 0) {
                    L.i { "[MessageArchiveManager] processed $totalProcessedCount messages for room ${room.roomId}" }
                    wcdb.message.getFirstObject(DBMessageModel.roomId.eq(room.roomId), DBMessageModel.systemShowTimestamp.order(Order.Asc))?.let { earliestMessage ->
                        L.i { "[MessageArchiveManager] created earlier messages expired message for room $room.roomId" }
                        ContactorUtil.createEarlierMessagesExpiredMessage(
                            room.roomId,
                            room.roomType,
                            earliestMessage.systemShowTimestamp - 1,
                            earliestMessage.readTime,
                            earliestMessage.expiresInSeconds
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
        currentTimeMillis: Long
    ): Expression? = when {
        // 有messageClearAnchor且messageExpiry > 0时，检查两个条件之一
        messageClearAnchor > 0 && messageExpiryMillis > 0 -> {
            val clearAnchorCondition = DBMessageModel.readTime.le(messageClearAnchor)
            val expiryCondition = DBMessageModel.readTime.add(messageExpiryMillis).lt(currentTimeMillis)
            baseCondition.and(clearAnchorCondition.or(expiryCondition))
        }
        // 只有messageClearAnchor时，只检查clearAnchor条件
        messageClearAnchor > 0 -> {
            baseCondition.and(DBMessageModel.readTime.le(messageClearAnchor))
        }
        // 只有messageExpiry > 0时，只检查过期条件
        messageExpiryMillis > 0 -> {
            baseCondition.and(DBMessageModel.readTime.add(messageExpiryMillis).lt(currentTimeMillis))
        }
        // 都没有时，不删除任何消息
        else -> null
    }

//    /**
//     * 处理基于expiresInSeconds过期的消息(旧版消息归档逻辑)
//     */
//    private suspend fun archiveMessagesByExpiry() {
//        val currentTimeMillis = System.currentTimeMillis()
//        val pageSize = 100L
//
//        try {
//            while (true) {
//                val expiredMessages = wcdb.message.getAllObjects(
//                    DBMessageModel.expiresInSeconds.gt(0)
//                        .and(DBMessageModel.readTime.gt(0))
//                        .and(DBMessageModel.readTime.add(DBMessageModel.expiresInSeconds.multiply(1000L)).lt(currentTimeMillis)),
//                    null,
//                    pageSize
//                )
//
//                if (expiredMessages.isNotEmpty()) {
//                    L.i { "[MessageArchiveManager] archiveMessages by expiry:" + expiredMessages.size + "===" + expiredMessages.map { it.timeStamp } }
//                    expiredMessages.forEach {
//                        it.delete()
//                    }
//                }
//
//                // 如果查询结果数量小于pageSize，说明已经是最后一批数据，不需要继续查询
//                if (expiredMessages.size < pageSize) {
//                    break
//                }
//
//                delay(100)
//            }
//
//
//        } catch (e: Exception) {
//            L.e { "[MessageArchiveManager] error archiving messages by expiry: ${e.stackTraceToString()}" }
//        }
//    }

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
                    dbRoomStore.updateMessageExpiry(forWhat, messageExpiry, response.data?.messageClearAnchor ?: 0L).blockingAwait()
                    MessageArchiveUtil.updateArchiveTime(forWhat.id, messageExpiry)
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
                    MessageArchiveUtil.updateArchiveTime(forWhat.id, messageExpiry)
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
                .concatMapCompletable {
                    if (it.status == 0) {
                        MessageArchiveUtil.updateArchiveTime(forWhat.id, messageExpiry)
//                        ApplicationDependencies.getRoomStore().updateMessageExpiry(forWhat, messageExpiry)
                        Completable.complete()
                    } else {
                        Completable.error(NetworkException(message = it.reason ?: ""))
                    }
                }
        } else {
            chatHttpClient.httpService
                .updateConversationConfig(
                    SecureSharedPrefsUtil.getToken(),
                    conversationParams(forWhat.id),
                    ConversationShareRequestBody(messageExpiry)
                )
                .concatMapCompletable {
                    if (it.status == 0) {
                        MessageArchiveUtil.updateArchiveTime(forWhat.id, messageExpiry)
//                        ApplicationDependencies.getRoomStore().updateMessageExpiry(forWhat, messageExpiry)
                        Completable.complete()
                    } else {
                        Completable.error(NetworkException(message = it.reason ?: ""))
                    }
                }
        }
    }

    @SuppressLint("CheckResult")
    fun updateLocalArchiveTime(forWhat: For, messageExpiry: Long, messageClearAnchor: Long) {
        dbRoomStore.updateMessageExpiry(forWhat, messageExpiry, messageClearAnchor)
            .compose(RxUtil.getCompletableTransformer())
            .subscribe({
                MessageArchiveUtil.updateArchiveTime(forWhat.id, messageExpiry)
            }, { it.stackTraceToString() })
    }

    /**
     * 清除所有小于等于指定时间的指定用户发送的消息
     * @param userId 用户ID，指定要删除哪个用户发送的消息
     * @param timestamp 时间戳，小于等于此时间的消息将被删除
     * @param pageSize 每页处理的消息数量，默认100条
     */
    private suspend fun clearMessagesBeforeTimestamp(userId: String, timestamp: Long, pageSize: Long = 100) {
        try {
            L.i { "[MessageArchiveManager] start clearing messages for user: $userId before timestamp: $timestamp" }
            while (true) {
                // 分页查询指定用户发送的消息
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

                // 如果查询结果数量小于pageSize，说明已经是最后一批数据，不需要继续查询
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

    //检查重置了identity key的用户，清理其对应的消息
    private suspend fun checkIdentityKeyReset() {
        try {
            // 获取当前最大的重置时间戳，以此获取增量的重置记录
            val maxResetTime = wcdb.resetIdentityKey.getValue(DBResetIdentityKeyModel.resetTime.max())?.long ?: 0L
            L.i { "[MessageArchiveManager] checkIdentityKeyReset maxResetTime: $maxResetTime" }

            val checkIdentityKeyResetResponse = chatHttpClient.httpService.getPublicKeys(
                SecureSharedPrefsUtil.getToken(),
                GetPublicKeysReq(beginTimestamp = maxResetTime)
            ).blockingFirst()
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
        if (operator == globalServices.myId) { //如果是自己重置identityKey，需要检查自己所有的1v1会话是否存在，创建提示消息
            wcdb.room.getAllObjects(DBRoomModel.roomId.notEq(globalServices.myId).and(DBRoomModel.roomType.eq(0)))
                .map { room ->
                    ContactorUtil.createResetIdentityKeyMessage(operator, For.Account(room.roomId), resetIdentityKeyTime, room.messageExpiry)
                }.let { messages ->
                    L.i { "[MessageArchiveManager] create reset identity key notify message for self, rooms size:${messages.size}" }
                    dbMessageStore.putWhenNonExist(*messages.toTypedArray())
                }
        } else {//如果不是自己重置identityKey，需要检查1v1会话是否存在，并创建提示消息
            wcdb.room.getFirstObject(DBRoomModel.roomId.eq(operator))?.let {
                L.i { "[MessageArchiveManager] create reset identity key message -> operator:${operator}  resetIdentityKeyTime:${resetIdentityKeyTime}" }
                ContactorUtil.createResetIdentityKeyMessage(operator, For.Account(operator), resetIdentityKeyTime, it.messageExpiry).let { message ->
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
            status = 1 // 标记为已处理
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

object MessageArchiveUtil {
    private val _archiveTimeUpdate = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 1)
    val archiveTimeUpdate: kotlinx.coroutines.flow.SharedFlow<Pair<String, Long>> = _archiveTimeUpdate.asSharedFlow()

    fun updateArchiveTime(id: String, time: Long) {
        _archiveTimeUpdate.tryEmit(id to time)
    }
}