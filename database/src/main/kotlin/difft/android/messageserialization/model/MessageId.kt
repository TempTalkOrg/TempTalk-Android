package difft.android.messageserialization.model

class MessageId (
    val sourceSenderId: String,
    val sourceSentTimeStamp: Long,
    val sourceSenderDeviceId: Int
) {
    val idValue: String =
        "$sourceSentTimeStamp${sourceSenderId.replace("+", "")}$sourceSenderDeviceId"
}