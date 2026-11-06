package com.difft.android.call.manager


import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.utils.appScope
import com.difft.android.call.LCallToChatController
import com.difft.android.network.HttpService
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话消息管理器
 * 负责统一管理通话相关的消息逻辑，包括：
 * - 发送或创建通话文本消息
 * - 移除待处理消息
 * - 获取联系人更新监听器
 */
@Singleton
class CallMessageManager @Inject constructor(
    private val callToChatController: LCallToChatController,
    @ChativeHttpClientModule.Chat private val chatHttpClient: dagger.Lazy<ChativeHttpClient>,
    private val messagingGate: MessagingGate,
) {

    private val chatHttpService by lazy {
        chatHttpClient.get().getService(HttpService::class.java)
    }

    /**
     * 发送或创建通话文本消息
     * 根据参数发送通话相关的文本消息，或创建本地消息记录
     * 
     * @param callActionType 通话操作类型（如开始、结束、挂断等）
     * @param textContent 消息文本内容
     * @param sourceDevice 来源设备ID
     * @param timestamp 消息时间戳
     * @param systemShowTime 系统显示时间戳
     * @param fromWho 发送者信息
     * @param forWhat 接收者信息（群组或用户）
     * @param callType 通话类型
     * @param createCallMsg 是否创建通话消息记录
     * @param inviteeList 被邀请者列表，默认为空列表
     */
    fun sendOrLocalCallTextMessage(
        callActionType: CallActionType,
        textContent: String,
        sourceDevice: Int,
        timestamp: Long,
        systemShowTime: Long,
        fromWho: For,
        forWhat: For,
        callType: CallType,
        createCallMsg: Boolean,
        inviteeList: List<String> = emptyList()
    ) {
        callToChatController.sendOrCreateCallTextMessage(
            callActionType,
            textContent,
            sourceDevice,
            timestamp,
            systemShowTime,
            fromWho,
            forWhat,
            callType,
            createCallMsg,
            inviteeList
        )
    }

    /**
     * 移除待处理消息
     * 从服务器删除指定来源和时间戳的待处理消息
     * 
     * @param source 消息来源（用户ID或群组ID）
     * @param timestamp 消息时间戳
     */
    fun removePendingMessage(source: String, timestamp: String) {
        appScope.launch(Dispatchers.IO) {
            // The delete runs after the processing that asked for it has finished, so it takes its
            // own ticket (#1382); while messaging is paused the call message stays queued.
            val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "call-delete")
            if (admission == null) {
                L.i { "[MigrationPause] event=receive_dropped source=call-delete count=1" }
                return@launch
            }
            admission.use { deletePendingMessage(source, timestamp) }
        }
    }

    private suspend fun deletePendingMessage(source: String, timestamp: String) {
        try {
            val result = chatHttpService.removePendingMessage(
                source,
                timestamp
            )

            if (result.isSuccess()) {
                L.i { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp success" }
            } else {
                L.e { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp failed -> ${result.reason}" }
            }
        } catch (e: Exception) {
            L.e { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp failed -> ${e.message}" }
        }
    }

    fun getContactsUpdateListener(): Flow<List<String>> {
        return callToChatController.getContactsUpdateListener()
    }
}

