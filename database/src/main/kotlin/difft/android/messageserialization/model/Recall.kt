package difft.android.messageserialization.model

import java.io.Serializable

data class Recall(val realSource: RealSource?):Serializable

data class RealSource(val source: String, val sourceDevice: Int, val timestamp: Long, val serverTimestamp: Long):Serializable

fun RealSource.mapToMessageId(): MessageId = MessageId(
    sourceSenderId = source,
    sourceSentTimeStamp = timestamp,
    sourceSenderDeviceId = sourceDevice
)
