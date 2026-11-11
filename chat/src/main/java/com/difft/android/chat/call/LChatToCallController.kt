package com.difft.android.chat.call

import android.app.Activity
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import difft.android.messageserialization.For

interface LChatToCallController {
    fun startCall(activity: Activity, forWhat: For, chatRoomName: String?, onComplete: (Boolean, String?) -> Unit)

    





    fun handleCallMessage(message: SignalServiceDataClass, admission: MessagingTicket, fromServerPush: Boolean)

    fun handleCallEndNotification(roomId: String)
}