package com.difft.android.chat.call

import android.app.Activity
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import difft.android.messageserialization.For

interface LChatToCallController {
    fun startCall(activity: Activity, forWhat: For, chatRoomName: String?, onComplete: (Boolean, String?) -> Unit)

    fun handleCallMessage(message: SignalServiceDataClass)

    //取消会议通知
    fun handleCallEndNotification(roomId: String)
}