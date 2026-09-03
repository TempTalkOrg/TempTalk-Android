package com.difft.android.websocket.api.util

import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.model.MessageId
import util.Hex
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.util.Locale

fun SignalServiceProtos.DataMessage.getGroupId(): ByteArray? {
    return if (hasGroup()) {
        group.id.toByteArray()
    } else null
}

fun SignalServiceProtos.ReadPosition.toOutgoingReadPositionEntity() =
    NewOutgoingPushMessage.ReadPositionEntity(
        groupId.toString(),
        readAt,
        maxServerTime,
        maxNotifySequenceId,
        maxSequenceId
    )

fun ByteArray.transformGroupIdFromServerToLocal(): String {
    // Mirror iOS/Desktop: 16-byte legacy id -> "WEEK" + uppercase hex; everything else -> UTF-8 string.
    // String(bytes, Charset) never throws (malformed bytes -> U+FFFD), so no try/catch is needed.
    if (size == 16) {
        return "WEEK" + Hex.toStringCondensed(this).uppercase(Locale.getDefault())
    }
    if (size != 32 && size != 36) {
        L.w { "[GroupId] unexpected server group id length=$size, decoding as UTF-8" }
    }
    return String(this, Charsets.UTF_8)
}

fun String.transformGroupIdFromLocalToServer(): ByteArray {
    return if (this.startsWith("WEEK")) {
        Hex.fromStringCondensed(replace("WEEK", "").lowercase(Locale.getDefault()))
    } else {
        toByteArray()
    }
}

fun SignalServiceProtos.RealSource.mapToMessageId(): MessageId = MessageId(
    sourceSenderId = source,
    sourceSentTimeStamp = timestamp,
    sourceSenderDeviceId = sourceDevice
)