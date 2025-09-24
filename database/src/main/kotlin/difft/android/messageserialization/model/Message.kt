package difft.android.messageserialization.model

import difft.android.messageserialization.For
import java.io.Serializable

abstract class Message(
    var id: String,
    var fromWho: For,
    var forWhat: For,
    var systemShowTimestamp: Long,
    var timeStamp: Long,
    var receivedTimeStamp: Long,
    var sendType: Int,
    var expiresInSeconds: Int,
    var notifySequenceId: Long,
    var sequenceId: Long,
    var mode: Int,
) : Serializable

