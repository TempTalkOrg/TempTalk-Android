package com.difft.android.call

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.LCallConstants.CALL_NOTIFICATION_OPERATION_ACCEPT
import com.difft.android.base.call.LCallConstants.CALL_NOTIFICATION_OPERATION_REJECT
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class InComingNewCallActionReceiver: BroadcastReceiver() {

    @Inject
    lateinit var callToChatController: LCallToChatController

    @SuppressLint("NewApi")
    override fun onReceive(context: Context?, intent: Intent?) {
        L.d { "[Call] InComingNewCallActionReceiver onReceive" }
        when (intent?.action) {
            CALL_NOTIFICATION_OPERATION_REJECT -> {
                val callType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE)
                val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_ROOM_ID)
                val conversationId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CONVERSATION_ID)
                val callerId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALLER_ID)

                if(callType!=null && roomId!=null && callerId!=null){
                    // 如果是1v1 call,则向caller发送reject消息
                    if(callType == CallType.ONE_ON_ONE.type){
                        L.i { "[Call] InComingNewCallActionReceiver onReceive, CALL_NOTIFICATION_OPERATION_REJECT roomId:$roomId" }
                        LCallManager.removeCallData(roomId)
                        LCallManager.stopIncomingCallService(roomId, tag = "reject: local reject call")
                    }else{
                        LCallManager.stopIncomingCallService(roomId, tag = "reject: local reject call")
                    }
                    callToChatController.rejectCall(callerId, CallRole.CALLEE, callType, roomId, conversationId){}
                }
            }
            CALL_NOTIFICATION_OPERATION_ACCEPT -> {
                val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_ROOM_ID)
                if (roomId != null) {
                    L.i { "[Call] InComingNewCallActionReceiver onReceive, CALL_NOTIFICATION_OPERATION_ACCEPT roomId:$roomId" }
                    LCallManager.joinCall(application, roomId)
                } else {
                    L.e { "[Call] InComingNewCallActionReceiver missing roomId for accept action" }
                }
            }
        }
    }

}