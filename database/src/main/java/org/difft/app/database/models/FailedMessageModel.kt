package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Arrays
import java.util.Objects

@WCDBTableCoding
class FailedMessageModel {

    @WCDBField(isPrimary = true, isUnique = true)
    var timestamp: Long = 0

    @WCDBField(isNotNull = true)
    var messageEnvelopBytes: ByteArray = ByteArray(0)

    





    @WCDBField
    var retryCount: Int = 0

    






    @WCDBField
    @WCDBIndex
    var lastAttemptTime: Long = 0

    override fun equals(other: Any?): Boolean {
        if (other == null || javaClass != other.javaClass) return false
        other as FailedMessageModel
        return timestamp == other.timestamp &&
                retryCount == other.retryCount &&
                lastAttemptTime == other.lastAttemptTime &&
                Objects.deepEquals(messageEnvelopBytes, other.messageEnvelopBytes)
    }

    override fun hashCode(): Int =
        Objects.hash(timestamp, Arrays.hashCode(messageEnvelopBytes), retryCount, lastAttemptTime)
}
