package com.difft.android.chat.jobs

import android.util.Base64
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.MD5Utils
import com.difft.android.websocket.internal.push.NotificationType
import com.difft.android.websocket.internal.push.OutgoingPushMessage
import com.difft.android.websocket.internal.push.OutgoingPushMessage.PassThrough
import com.google.gson.Gson
import difft.android.messageserialization.For
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAttachmentMessage

/**
 * Builds the push-notification payload for an outgoing [TextMessage]. Shared by [PushTextSendJob]
 * and [PushScreenShotSendJob] so both emit the exact same payload for the same wire message.
 */
internal fun buildTextMessageNotification(
    textMessage: TextMessage,
    gson: Gson
): OutgoingPushMessage.Notification {
    val collapseId = MD5Utils.md5AndHexStr(textMessage.timeStamp.toString() + textMessage.fromWho.id + DEFAULT_DEVICE_ID)
    val conversationId = if (textMessage.forWhat is For.Group) {
        Base64.encodeToString(textMessage.forWhat.id.toByteArray(), Base64.NO_WRAP)
    } else {
        textMessage.fromWho.id
    }
    val passThrough = PassThrough(conversationId)
    var mentionedPersons: Array<String>? = null
    val type: Int = if (textMessage.forWhat is For.Group) {
        if (textMessage.recall != null) {
            NotificationType.RECALL_MSG.code
        } else if (!textMessage.mentions.isNullOrEmpty()) {
            if (textMessage.mentions?.firstOrNull()?.uid == MENTIONS_ALL_ID) {
                NotificationType.GROUP_MENTIONS_ALL.code
            } else {
                mentionedPersons = textMessage.mentions?.mapNotNull { it.uid }?.toTypedArray()
                NotificationType.GROUP_MENTIONS_DESTINATION.code
            }
        } else {
            if (textMessage.isAttachmentMessage()) {
                NotificationType.GROUP_FILE.code
            } else {
                NotificationType.GROUP_NORMAL.code
            }
        }
    } else {
        if (textMessage.recall != null) {
            NotificationType.RECALL_MSG.code
        } else {
            if (textMessage.isAttachmentMessage()) {
                NotificationType.PERSONAL_FILE.code
            } else {
                NotificationType.PERSONAL_NORMAL.code
            }
        }
    }

    return OutgoingPushMessage.Notification(
        OutgoingPushMessage.Args(
            if (textMessage.forWhat is For.Group) textMessage.forWhat.id else "",
            collapseId,
            gson.toJson(passThrough),
            mentionedPersons
        ),
        type
    )
}
