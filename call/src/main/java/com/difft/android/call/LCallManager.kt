package com.difft.android.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.ServiceUrls
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
import kotlinx.coroutines.flow.Flow





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


    






    suspend fun getCriticalAlertNotificationContent(
        conversationId: String,
        sourceId: String
    ): Pair<String, String> {
        return contactorCacheManager.getCriticalAlertNotificationContent(conversationId, sourceId)
    }

    






    suspend fun joinCall(context: Context, callData: CallData): Boolean {
        if (inComingCallStateManager.isActivityShowing()) {
            try {
                callToChatController?.getIncomingCallRoomId()?.let { roomId ->
                    stopIncomingCallService(roomId, "stop incoming call")
                }
            } catch (e: Exception) {
                L.e(e) { "[Call] LCallManager joinCall stopIncomingCallService error:" }
            }
        }

        val id = callData.roomId
        val callType = CallType.fromString(callData.type.toString()) ?: CallType.ONE_ON_ONE
        val callerId = callData.caller.uid
        val conversationId = callData.conversation
        val callName = callData.callName

        if (id.isNotEmpty() && !callerId.isNullOrEmpty()) {
            return callToChatController.joinCall(
                context = context,
                roomId = id,
                roomName = callName,
                callerId = callerId,
                callType = callType,
                conversationId = conversationId,
                isNeedAppLock = false
            )
        }
        return false
    }

    




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

    





    fun stopIncomingCallService(roomId: String, tag: String? = null) {
        incomingCallServiceManager.stopIncomingCallService(application, roomId, tag)
    }

    




    fun startIncomingCallService(intent: Intent) {
        incomingCallServiceManager.startIncomingCallService(application, intent)
    }

    
    fun restoreIncomingCallScreenIfActive() {
        callToChatController.restoreIncomingCallScreenIfActive()
    }

    













    fun sendOrLocalCallTextMessage(callActionType: CallActionType, textContent: String, sourceDevice: Int, timestamp: Long, systemShowTime: Long, fromWho: For, forWhat: For, callType: CallType, createCallMsg: Boolean, inviteeLIst: List<String> = emptyList()) {
        callMessageManager.sendOrLocalCallTextMessage(callActionType, textContent, sourceDevice, timestamp, systemShowTime, fromWho, forWhat, callType, createCallMsg, inviteeLIst)
    }

    





    fun removePendingMessage(source: String, timestamp: String) {
        callMessageManager.removePendingMessage(source, timestamp)
    }

    fun getContactsUpdateListener(): Flow<List<String>> {
        return callMessageManager.getContactsUpdateListener()
    }

    




    suspend fun fetchCallServiceUrlAndCache(): List<String> {
        return callServiceUrlManager.fetchCallServiceUrlAndCache()
    }

    
    fun onAppForegroundedForCallServiceUrls() {
        callServiceUrlManager.onAppForegrounded()
    }

    
    suspend fun refreshCallServiceUrlsAfterConnectionFailure() {
        callServiceUrlManager.refreshAfterConnectionFailure()
    }

    
    suspend fun ensureCallServiceUrlsForCall(timeoutMs: Long = 15_000L): ServiceUrls? {
        return callServiceUrlManager.ensureServiceUrlsForCall(timeoutMs)
    }

    
    fun getCachedServiceUrls(): ServiceUrls? {
        return callServiceUrlManager.getCachedServiceUrls()
    }

    




    fun getUpstreamCachedServiceUrls(): ServiceUrls? {
        return callServiceUrlManager.getUpstreamCachedServiceUrls()
    }

    




    fun getAndClearCallFeedbackInfo(): FeedbackCallInfo? {
        return callFeedbackManager.getAndClearCallFeedbackInfo()
    }

    





    fun showCallFeedbackView(activity: Activity, callInfo: FeedbackCallInfo) {
        return callFeedbackManager.showCallFeedbackView(activity, callInfo)
    }

    
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

    
    fun dismissCriticalAlertIfActive() {
        callToChatController.dismissCriticalAlertIfActive()
    }

    




    fun dismissCriticalAlert(conversationId: String) {
        callToChatController.dismissCriticalAlert(conversationId)
    }

    




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