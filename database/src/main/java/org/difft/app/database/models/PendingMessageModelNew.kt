package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBTableCoding
import java.util.Arrays
import java.util.Objects

@WCDBTableCoding
class PendingMessageModelNew {

    @WCDBField(isPrimary = true, isUnique = true)
    var messageId: String? = null

    @WCDBField
    var originalMessageTimeStamp: Long = 0

    @WCDBField(isNotNull = true)
    var messageEnvelopBytes: ByteArray = ByteArray(0)

    override fun equals(other: Any?): Boolean {
        if (other == null || javaClass != other.javaClass) return false
        other as PendingMessageModelNew
        return messageId == other.messageId &&
                originalMessageTimeStamp == other.originalMessageTimeStamp &&
                Objects.deepEquals(messageEnvelopBytes, other.messageEnvelopBytes)
    }

    override fun hashCode(): Int =
        Objects.hash(messageId, originalMessageTimeStamp, Arrays.hashCode(messageEnvelopBytes))
}
