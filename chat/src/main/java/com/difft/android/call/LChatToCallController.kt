package com.difft.android.call

import android.app.Activity
import android.content.Context
import difft.android.messageserialization.For
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import java.util.ArrayList

interface LChatToCallController {
    fun startCall(activity: Activity, forWhat: For, chatRoomName: String?)

    fun handleCallMessage(message: SignalServiceDataClass)

    //取消会议通知
    fun handleCallEndNotification(roomId: String)

    fun inviteCall(context: Context, roomId: String, roomName: String?, callType: String?, mKey: ByteArray?, inviteMembers: ArrayList<String>, conversationId: String?)
}