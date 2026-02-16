package com.difft.android.call

import android.app.Activity
import difft.android.messageserialization.For
import com.difft.android.websocket.api.messages.SignalServiceDataClass

interface LChatToCallController {
    fun startCall(activity: Activity, forWhat: For, chatRoomName: String?, onComplete: (Boolean, String?) -> Unit)

    fun handleCallMessage(message: SignalServiceDataClass)

    //取消会议通知
    fun handleCallEndNotification(roomId: String)
}