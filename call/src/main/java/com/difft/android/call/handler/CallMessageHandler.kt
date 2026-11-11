package com.difft.android.call.handler

import android.content.Intent
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.CriticalAlertManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.session.command.CallSessionCommands
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupRepo
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class CallMessageHandler @Inject constructor(
    @param:ChativeHttpClientModule.Call
    private val callHttpClient: ChativeHttpClient,
    internal val callDataManager: CallDataManager,
    internal val onGoingCallStateManager: OnGoingCallStateManager,
    internal val inComingCallStateManager: InComingCallStateManager,
    internal val contactorCacheManager: ContactorCacheManager,
    internal val callToChatController: LCallToChatController,
    internal val groupRepo: GroupRepo,
    internal val dbRoomStore: DBRoomStore,
    internal val criticalAlertManager: CriticalAlertManager,
    
    private val callSessionCommands: Lazy<CallSessionCommands>,
    internal val messagingGate: MessagingGate,
) {
    internal val callService: LCallHttpService by lazy {
        callHttpClient.getService(LCallHttpService::class.java)
    }

    internal val mySelfId: String by lazy {
        globalServices.myId
    }

    companion object {
        internal const val RESPONSE_STATUS_SUCCESS = 0
    }

    





    fun handleCallMessage(message: SignalServiceDataClass, admission: MessagingTicket, fromServerPush: Boolean = false) {
        val envelope = message.signalServiceEnvelope
        val content = message.signalServiceContent?.callMessage
        val roomId = envelope.roomId

        L.d { "[Call] handleCallMessage, envelope.content:${content}" }
        L.i { "[Call] handleCallMessage, receive call message envelope.timestamp:${envelope.timestamp} roomId:${roomId}" }

        if (roomId.isNullOrEmpty()) {
            L.e { "[Call] handleCallMessage, envelope roomId is Null Or Empty" }
            return
        }

        when {
            content?.hasCalling() == true -> handleCallingMessage(message, envelope, content, roomId, admission, fromServerPush)
            content?.hasJoined() == true -> handleJoinedMessage(content, roomId, admission)
            content?.hasCancel() == true -> handleCancelMessage(content, roomId)
            content?.hasReject() == true -> handleRejectMessage(message, content, roomId)
            content?.hasHangup() == true -> handleHangupMessage(content, roomId)
        }
    }

    


    fun handleCallEndNotification(roomId: String) {
        if (roomId.isEmpty()) return

        
        criticalAlertManager.dismissForRoom(roomId)

        callDataManager.removeCallData(roomId)
        callToChatController.cancelNotificationById(roomId.hashCode())

        cancelNotificationAndHandleService(roomId, CallActionType.CALLEND, "ended: server call end notification")

        if (onGoingCallStateManager.isInCalling() && onGoingCallStateManager.getCurrentRoomId() == roomId) {
            endOngoingCall(CallActionType.CALLEND, roomId)
        }
    }

    

    



    internal fun cancelNotificationAndHandleService(
        roomId: String,
        actionType: CallActionType,
        tag: String,
    ) {
        callToChatController.cancelNotificationById(roomId.hashCode())

        if (inComingCallStateManager.isActivityShowing()) {
            updateControlMessage(actionType, roomId)
        } else {
            LCallManager.stopIncomingCallService(roomId, tag = tag)
        }
    }

    
    internal fun endOngoingCall(actionType: CallActionType, roomId: String) =
        callSessionCommands.get().onRemoteControl(actionType, roomId)

    
    internal fun updateControlMessage(actionType: CallActionType, roomId: String) {
        val controlMessage = OnGoingCallStateManager.ControlMessage(
            actionType = actionType,
            roomId = roomId,
        )
        onGoingCallStateManager.updateControlMessage(controlMessage)
    }

    internal suspend fun checkUserIsInGroup(userId: String, gid: String): Boolean {
        return try {
            val resp = groupRepo.getGroupInfo(gid)
            val members = resp.data?.members.orEmpty()
            members.any { it.uid == userId }
        } catch (e: CancellationException) {
            
            throw e
        } catch (e: Exception) {
            L.e { "[Call] CallMessageHandler checkUserIsInGroup error: ${e.stackTraceToString()}" }
            false
        }
    }

    internal suspend fun getGroupNameSafely(conversationId: String): String? {
        return try {
            callToChatController.getSingleGroupInfo(conversationId)?.name
        } catch (e: CancellationException) {
            
            throw e
        } catch (e: Exception) {
            L.e { "[Call] CallMessageHandler getGroupNameSafely error: ${e.stackTraceToString()}" }
            null
        }
    }

    





    internal fun checkIfShowIncomingCall(
        anotherDeviceJoined: Boolean,
        msgSenderId: String,
        callData: CallData,
    ): Boolean {
        return !anotherDeviceJoined &&
                msgSenderId != mySelfId &&
                !callToChatController.isIncomingCallNotifying(callData.roomId) &&
                onGoingCallStateManager.getCurrentRoomId() != callData.roomId
    }

    





    internal suspend fun showIncomingNotificationOrActivity(callData: CallData, fromServerPush: Boolean) {
        if (!fromServerPush && callData.type == CallType.GROUP.type) {
            val gid = callData.conversation
            if (gid != null && !callToChatController.shouldNotifyGroupCall(gid)) {
                L.i { "[Call] incoming group call not alerted by notify policy roomId=${callData.roomId}" }
                return
            }
        }
        presentIncomingCall(callData)
    }

    private suspend fun presentIncomingCall(callData: CallData) = withContext(Dispatchers.Main) {
        
        
        if (!messagingGate.isOpen) {
            L.i { "[Call] event=incoming_ring_skipped reason=messaging_paused roomId=${callData.roomId}" }
            return@withContext
        }
        val intentNotify = Intent().apply {
            putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, callData.type)
            putExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE, CallRole.CALLEE.type)
            putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, callData.roomId)
            putExtra(LCallConstants.BUNDLE_KEY_CALL_NAME, callData.callName)
            putExtra(LCallConstants.BUNDLE_KEY_CALLER_ID, callData.caller.uid)
            putExtra(LCallConstants.BUNDLE_KEY_CONVERSATION_ID, callData.conversation)
        }
        LCallManager.startIncomingCallService(intentNotify)
    }

    
}
