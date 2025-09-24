package com.difft.android.websocket.api.util

import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.model.MessageId
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
    return when (size) {
//        16 -> {
//            "WEEK" + Hex.toStringCondensed(this).uppercase(Locale.getDefault())
//        }
        32, 36 -> {
            String(this, Charsets.UTF_8)
        }

        else -> {
            val utf8Result = try {
                String(this, Charsets.UTF_8)
            } catch (e: Exception) {
                L.e { "group id UTF8 conversion failed: ${e.message}" }
                null
            }

            val hexResult = Hex.toStringCondensed(this)

            // 上报异常，包含两种转换结果对比
            val errorMsg = "Invalid group id length: $size, UTF8: ${utf8Result ?: "FAILED"}, Hex: $hexResult"
            L.e { errorMsg }
            FirebaseCrashlytics.getInstance().recordException(IllegalArgumentException(errorMsg))

            utf8Result ?: hexResult
        }
    }
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