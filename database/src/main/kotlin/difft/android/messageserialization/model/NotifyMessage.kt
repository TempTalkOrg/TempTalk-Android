package difft.android.messageserialization.model

import difft.android.messageserialization.For

class NotifyMessage(
    id: String,
    fromWho: For,
    forWhat: For,
    systemShowTimestamp: Long,
    timeStamp: Long,
    receivedTimeStamp: Long,
    sendType: Int,
    expiresInSeconds: Int,
    notifySequenceId: Long,
    sequenceId: Long,
    mode: Int,
    var notifyContent: String,//json   SignalNotifyMessage
) : Message(
    id,
    fromWho,
    forWhat,
    systemShowTimestamp,
    timeStamp,
    receivedTimeStamp,
    sendType,
    expiresInSeconds,
    notifySequenceId,
    sequenceId,
    mode,
)
