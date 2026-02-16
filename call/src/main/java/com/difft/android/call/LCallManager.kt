package com.difft.android.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import difft.android.messageserialization.For
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallFeedbackManager
import com.difft.android.call.manager.CallMessageManager
import com.difft.android.call.manager.CallServiceUrlManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.IncomingCallServiceManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.network.config.FeatureGrayManager
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.reactivex.rxjava3.core.Observable

/**
 * 通话管理器
 * 
 * 作为通话模块的门面（Facade），提供统一的接口访问各种通话相关的功能。
 * 内部通过委托模式将具体功能实现分散到各个专门的管理器类中。
 *
 */
object LCallManager {

    private var application = ApplicationHelper.instance

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val callToChatController: LCallToChatController
        val onGoingCallStateManager: OnGoingCallStateManager
        val callDataManager: CallDataManager
        val inComingCallStateManager: InComingCallStateManager
        val contactorCacheManager: ContactorCacheManager
        val callServiceUrlManager: CallServiceUrlManager
        val callFeedbackManager: CallFeedbackManager
        val incomingCallServiceManager: IncomingCallServiceManager
        val callMessageManager: CallMessageManager
    }

    /**
     * 统一的 EntryPoint 访问器
     * 所有依赖都通过此属性获取，避免重复的 EntryPointAccessors 调用
     */
    private val entryPoint: EntryPoint by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance)
    }

    private val callToChatController: LCallToChatController by lazy {
        entryPoint.callToChatController
    }

    private val inComingCallStateManager: InComingCallStateManager by lazy {
        entryPoint.inComingCallStateManager
    }

    private val callDataManager: CallDataManager by lazy {
        entryPoint.callDataManager
    }

    private val contactorCacheManager: ContactorCacheManager by lazy {
        entryPoint.contactorCacheManager
    }

    private val callServiceUrlManager: CallServiceUrlManager by lazy {
        entryPoint.callServiceUrlManager
    }

    private val callFeedbackManager: CallFeedbackManager by lazy {
        entryPoint.callFeedbackManager
    }

    private val incomingCallServiceManager: IncomingCallServiceManager by lazy {
        entryPoint.incomingCallServiceManager
    }

    private val callMessageManager: CallMessageManager by lazy {
        entryPoint.callMessageManager
    }


    /**
     * 获取关键提醒通知内容
     * 
     * @param conversationId 会话ID
     * @param sourceId 来源ID（群组ID或用户ID）
     * @return 返回标题和内容的 Pair，用于显示关键提醒通知
     */
    suspend fun getCriticalAlertNotificationContent(
        conversationId: String,
        sourceId: String
    ): Pair<String, String> {
        return contactorCacheManager.getCriticalAlertNotificationContent(conversationId, sourceId)
    }

    /**
     * 加入通话
     * 根据通话数据加入指定的通话房间
     * 
     * @param context 上下文
     * @param callData 通话数据，包含房间ID、通话类型、呼叫者ID等信息
     * @param onComplete 完成回调，参数为 true 表示成功，false 表示失败
     */
    fun joinCall(context: Context, callData: CallData, onComplete: (Boolean) -> Unit) {

        if(inComingCallStateManager.isActivityShowing()) {
            try {
                callToChatController?.getIncomingCallRoomId()?.let { roomId ->
                    stopIncomingCallService(roomId, "stop incoming call")
                }
            } catch (e: Exception) {
                L.e(e) { "[Call] LCallManager joinCall stopIncomingCallService error:" }
            }
        }

        callData.let {
            val id = it.roomId
            val callType = CallType.fromString(it.type.toString()) ?: CallType.ONE_ON_ONE
            val callerId = it.caller.uid
            val conversationId = it.conversation
            val callName = it.callName
            if (id.isNotEmpty() && !callerId.isNullOrEmpty()) {
                callToChatController.joinCall(
                    context = context,
                    roomId = id,
                    roomName = callName,
                    callerId = callerId,
                    callType = callType,
                    conversationId = conversationId,
                    isNeedAppLock = false
                ) { status ->
                    onComplete(status)
                }
            }
        }
    }

    /**
     * 将通话界面带到前台
     * 如果通话界面在后台，将其重新显示到前台
     * 
     * @param context 上下文
     */
    fun bringCallScreenToFront(context: Context) {
        try {
            L.i { "[call] LCallManger bringCallScreenToFront" }
            val intent = CallIntent.Builder(context, LCallActivity::class.java)
                .withAction(CallIntent.Action.BACK_TO_CALL)
                .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .build()
            context.startActivity(intent)
        } catch (e: Exception) {
            L.e(e) { "[call] LCallManger bringCallScreenToFront fail:" }
        }
    }

    /**
     * 停止来电服务
     * 停止指定房间的来电通知、铃声、震动等，并发送销毁广播
     * 
     * @param roomId 房间ID
     * @param tag 停止原因标签，用于日志记录，可为 null
     */
    fun stopIncomingCallService(roomId: String, tag: String? = null) {
        incomingCallServiceManager.stopIncomingCallService(application, roomId, tag)
    }

    /**
     * 启动来电服务
     * 处理来电请求，显示来电界面或通知，播放铃声和震动，启动超时检测
     * 
     * @param intent 包含来电信息的 Intent，需要包含房间ID、通话类型、呼叫者ID等信息
     */
    fun startIncomingCallService(intent: Intent) {
        incomingCallServiceManager.startIncomingCallService(application, intent)
    }

    /**
     * 恢复来电界面（如果活跃）
     * 如果来电界面在后台，将其恢复到前台显示
     */
    fun restoreIncomingCallScreenIfActive() {
        callToChatController.restoreIncomingCallScreenIfActive()
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
     * @param inviteeLIst 被邀请者列表，默认为空列表
     */
    fun sendOrLocalCallTextMessage(callActionType: CallActionType, textContent: String, sourceDevice: Int, timestamp: Long, systemShowTime: Long, fromWho: For, forWhat: For, callType: CallType, createCallMsg: Boolean, inviteeLIst: List<String> = emptyList()) {
        callMessageManager.sendOrLocalCallTextMessage(callActionType, textContent, sourceDevice, timestamp, systemShowTime, fromWho, forWhat, callType, createCallMsg, inviteeLIst)
    }

    /**
     * 移除待处理消息
     * 从服务器删除指定来源和时间戳的待处理消息
     * 
     * @param source 消息来源（用户ID或群组ID）
     * @param timestamp 消息时间戳
     */
    fun removePendingMessage(source: String, timestamp: String) {
        callMessageManager.removePendingMessage(source, timestamp)
    }

    /**
     * 获取联系人更新监听器
     * 返回一个 Observable，用于监听联系人列表的变化
     * 
     * @return Observable<List<String>> 联系人ID列表的 Observable
     */
    fun getContactsUpdateListener(): Observable<List<String>> {
        return callMessageManager.getContactsUpdateListener()
    }

    /**
     * 从服务器获取通话服务URL并缓存
     * 异步从服务器获取通话服务URL列表，并更新本地缓存
     * 
     * @return 服务URL列表，如果获取失败则返回空列表
     */
    suspend fun fetchCallServiceUrlAndCache(): List<String> {
        return callServiceUrlManager.fetchCallServiceUrlAndCache()
    }

    /**
     * 获取缓存的服务URL列表
     * 返回当前缓存的服务URL列表，不会发起网络请求
     * 
     * @return 服务URL列表的副本，如果缓存为空则返回空列表
     */
    fun getCallServiceUrl(): List<String> {
        return callServiceUrlManager.getCallServiceUrl()
    }

    /**
     * 获取并清空通话反馈信息
     * 原子操作：获取当前反馈信息后立即清空
     * 
     * @return 当前的反馈信息，如果不存在则返回 null
     */
    fun getAndClearCallFeedbackInfo(): FeedbackCallInfo? {
        return callFeedbackManager.getAndClearCallFeedbackInfo()
    }

    /**
     * 显示通话反馈视图
     * 在指定的 Activity 上显示通话反馈界面
     * 
     * @param activity 要显示反馈视图的 Activity
     * @param callInfo 反馈信息，包含通话相关数据
     */
    fun showCallFeedbackView(activity: Activity, callInfo: FeedbackCallInfo) {
        return callFeedbackManager.showCallFeedbackView(activity, callInfo)
    }

    /**
     * 检查 QUIC 功能灰度状态
     * 根据灰度配置决定使用 HTTP3/QUIC 还是 WebSocket 连接方式
     */
    suspend fun checkQuicFeatureGrayStatus() {
        try {
            if (LCallEngine.hasManualConnectionTypeOverride()) {
                L.i { "[call] LCallManager checkQuicFeatureGrayStatus skipped: manual connection type override is enabled." }
                return
            }
            val type = if (FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK)) CONNECTION_TYPE.HTTP3_QUIC else CONNECTION_TYPE.WEB_SOCKET
            L.i { "[call] LCallManager checkQuicFeatureGrayStatus: $type" }
            LCallEngine.setSelectedConnectMode(type)
        } catch (e: Exception) {
            L.e { "[Call] LCallManager checkQuicFeatureGrayStatus error: ${e.message}" }
        }
    }

    /**
     * 关闭关键提醒（如果活跃）
     * 如果当前有关键提醒正在显示，则关闭它
     */
    fun dismissCriticalAlertIfActive() {
        callToChatController.dismissCriticalAlertIfActive()
    }

    /**
     * 关闭指定会话的关键提醒
     * 
     * @param conversationId 会话ID
     */
    fun dismissCriticalAlert(conversationId: String) {
        callToChatController.dismissCriticalAlert(conversationId)
    }

    /**
     * 关闭指定会话的来电通知
     * 根据会话ID查找对应的通话数据，并停止来电服务
     * 
     * @param conversationId 会话ID
     */
    fun dismissIncomingNotification(conversationId: String) {
        val callData = callDataManager.getCallDataByConversationId(conversationId)
        callData?.roomId?.let { roomId ->
            stopIncomingCallService(roomId = roomId, tag = "critical alert dismiss notification.")
        }
    }

    fun dismissIncomingNotificationByRoomId(roomId: String) {
        stopIncomingCallService(roomId = roomId, tag = "critical alert dismiss notification.")
    }
}