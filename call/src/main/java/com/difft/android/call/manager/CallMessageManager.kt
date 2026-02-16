package com.difft.android.call.manager

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.call.LCallToChatController
import com.difft.android.network.HttpService
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.base.utils.ApplicationHelper
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
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
    private val callToChatController: LCallToChatController
) {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Chat
        fun chatHttpClient(): ChativeHttpClient
    }

    private val chatHttpService by lazy {
        val chatHttpClient = EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).chatHttpClient()
        chatHttpClient.getService(HttpService::class.java)
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
            try {
                val result = chatHttpService.removePendingMessage(
                    SecureSharedPrefsUtil.getBasicAuth(),
                    source,
                    timestamp
                ).await()

                if (result.isSuccess()) {
                    L.i { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp success" }
                } else {
                    L.e { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp failed -> ${result.reason}" }
                }
            } catch (e: Exception) {
                L.e { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp failed -> ${e.message}" }
            }
        }
    }

    /**
     * 获取联系人更新监听器
     * 返回一个 Observable，用于监听联系人列表的变化
     * 
     * @return Observable<List<String>> 联系人ID列表的 Observable
     */
    fun getContactsUpdateListener(): Observable<List<String>> {
        return callToChatController.getContactsUpdateListener()
    }
}

