package com.difft.android.websocket.util

import difft.android.messageserialization.For
import com.google.protobuf.ByteString
import com.difft.android.websocket.api.util.transformGroupIdFromLocalToServer
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.copy

fun SignalServiceProtos.Envelope.copyWithMsgExtraConversationId(conversation: For) = copy {
    msgExtra = msgExtra.copy {
        conversationId = conversationId.copy {
            if (conversation is For.Account) {
                number = conversation.id
            } else if (conversation is For.Group) {
                groupId = ByteString.copyFrom(conversation.id.transformGroupIdFromLocalToServer())
            }
        }
    }
}