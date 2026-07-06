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

/**
 * Call manager — facade for the call module, providing a unified interface to call-related features.
 * Delegates concrete implementations to specialized manager classes.
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

    /** Unified EntryPoint accessor — all dependencies are obtained through this to avoid redundant EntryPointAccessors calls. */
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
     * Returns critical alert notification content.
     *
     * @param conversationId conversation ID
     * @param sourceId source ID (group ID or user ID)
     * @return a Pair of title and body for the critical alert notification
     */
    suspend fun getCriticalAlertNotificationContent(
        conversationId: String,
        sourceId: String
    ): Pair<String, String> {
        return contactorCacheManager.getCriticalAlertNotificationContent(conversationId, sourceId)
    }

    /**
     * Joins a call room based on the given call data.
     *
     * @param context context
     * @param callData call data containing room ID, call type, caller ID, etc.
     * @return `true` if the call was joined successfully, `false` otherwise
     */
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

    /**
     * Brings the call screen to the foreground.
     *
     * @param context context
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
     * Stops the incoming call service for the given room (notification, ringtone, vibration, etc.) and sends a destroy broadcast.
     *
     * @param roomId room ID
     * @param tag reason tag for logging, nullable
     */
    fun stopIncomingCallService(roomId: String, tag: String? = null) {
        incomingCallServiceManager.stopIncomingCallService(application, roomId, tag)
    }

    /**
     * Starts the incoming call service — shows the incoming call UI/notification, plays ringtone and vibration, and starts timeout detection.
     *
     * @param intent Intent containing incoming call info (room ID, call type, caller ID, etc.)
     */
    fun startIncomingCallService(intent: Intent) {
        incomingCallServiceManager.startIncomingCallService(application, intent)
    }

    /** Restores the incoming call screen to the foreground if it is currently active. */
    fun restoreIncomingCallScreenIfActive() {
        callToChatController.restoreIncomingCallScreenIfActive()
    }

    /**
     * Sends or creates a local call text message.
     *
     * @param callActionType call action type (start, end, hangup, etc.)
     * @param textContent message text content
     * @param sourceDevice source device ID
     * @param timestamp message timestamp
     * @param systemShowTime system display timestamp
     * @param fromWho sender info
     * @param forWhat recipient info (group or user)
     * @param callType call type
     * @param createCallMsg whether to create a call message record
     * @param inviteeLIst invitee list, defaults to empty
     */
    fun sendOrLocalCallTextMessage(callActionType: CallActionType, textContent: String, sourceDevice: Int, timestamp: Long, systemShowTime: Long, fromWho: For, forWhat: For, callType: CallType, createCallMsg: Boolean, inviteeLIst: List<String> = emptyList()) {
        callMessageManager.sendOrLocalCallTextMessage(callActionType, textContent, sourceDevice, timestamp, systemShowTime, fromWho, forWhat, callType, createCallMsg, inviteeLIst)
    }

    /**
     * Removes a pending message from the server by source and timestamp.
     *
     * @param source message source (user ID or group ID)
     * @param timestamp message timestamp
     */
    fun removePendingMessage(source: String, timestamp: String) {
        callMessageManager.removePendingMessage(source, timestamp)
    }

    fun getContactsUpdateListener(): Flow<List<String>> {
        return callMessageManager.getContactsUpdateListener()
    }

    /**
     * Fetches call service URLs from the server and updates the local cache.
     *
     * @return service URL list; empty list on failure
     */
    suspend fun fetchCallServiceUrlAndCache(): List<String> {
        return callServiceUrlManager.fetchCallServiceUrlAndCache()
    }

    /** Called by Application when the app returns to the foreground; refreshes [getServiceUrlV2] cache at intervals. */
    fun onAppForegroundedForCallServiceUrls() {
        callServiceUrlManager.onAppForegrounded()
    }

    /** Force-refreshes domain config after a connection failure. */
    suspend fun refreshCallServiceUrlsAfterConnectionFailure() {
        callServiceUrlManager.refreshAfterConnectionFailure()
    }

    /** Before initiating a call: waits for fetch within timeout if cache is missing/expired; returns complete [ServiceUrls] or null. */
    suspend fun ensureCallServiceUrlsForCall(timeoutMs: Long = 15_000L): ServiceUrls? {
        return callServiceUrlManager.ensureServiceUrlsForCall(timeoutMs)
    }

    /** Returns the last persisted [ServiceUrls] (no expiration check), used to continue connection after a forced refresh. */
    fun getCachedServiceUrls(): ServiceUrls? {
        return callServiceUrlManager.getCachedServiceUrls()
    }

    /**
     * Atomically gets and clears the call feedback info.
     *
     * @return current feedback info, or null if none
     */
    fun getAndClearCallFeedbackInfo(): FeedbackCallInfo? {
        return callFeedbackManager.getAndClearCallFeedbackInfo()
    }

    /**
     * Shows the call feedback view on the given activity.
     *
     * @param activity the activity to display the feedback view on
     * @param callInfo feedback info containing call-related data
     */
    fun showCallFeedbackView(activity: Activity, callInfo: FeedbackCallInfo) {
        return callFeedbackManager.showCallFeedbackView(activity, callInfo)
    }

    /** Checks QUIC feature flag status and sets the connection mode to HTTP3/QUIC or WebSocket accordingly. */
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

    /** Dismisses the critical alert if one is currently active. */
    fun dismissCriticalAlertIfActive() {
        callToChatController.dismissCriticalAlertIfActive()
    }

    /**
     * Dismisses the critical alert for the given conversation.
     *
     * @param conversationId conversation ID
     */
    fun dismissCriticalAlert(conversationId: String) {
        callToChatController.dismissCriticalAlert(conversationId)
    }

    /**
     * Dismisses the incoming call notification for the given conversation.
     *
     * @param conversationId conversation ID
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