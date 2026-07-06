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

    /**
     * Retry attempt counter. Starts at 0, increments per retry tick attempt
     * that ends in TransientFailure. Row is deleted when it would reach
     * `MAX_RETRIES` (see `FailedMessageProcessor#bumpRetryOrGiveUp`).
     * No index — cardinality is 0..4, too low for an index to pay back.
     */
    @WCDBField
    var retryCount: Int = 0

    /**
     * Wall-clock (ms) of the most recent attempt. New inserts use `System.currentTimeMillis()`;
     * legacy rows (pre-upgrade) default to 0, which both schedules them as immediately due
     * and trips the first-cycle TTL sweep (see `FailedMessageProcessor`).
     * Indexed because the scheduler issues
     * `WHERE retryCount < N ORDER BY lastAttemptTime ASC LIMIT M`.
     */
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
