package com.difft.android.chat.call

import android.app.Activity
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import difft.android.messageserialization.For

interface LChatToCallController {
    fun startCall(activity: Activity, forWhat: For, chatRoomName: String?, onComplete: (Boolean, String?) -> Unit)

    /**
     * @param admission the envelope's open receive ticket; asynchronous work that persists from
     *   the message retains its own ticket from it (#1382).
     * @param fromServerPush true for a message delivered by FCM: the server already decided whether this
     *   device may be alerted, so a group call is not re-judged by the local notify policy.
     */
    fun handleCallMessage(message: SignalServiceDataClass, admission: MessagingTicket, fromServerPush: Boolean)

    //取消会议通知
    fun handleCallEndNotification(roomId: String)
}