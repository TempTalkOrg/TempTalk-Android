package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Arrays;
import java.util.Objects;

@WCDBTableCoding
public class FailedMessageModel {

    @WCDBField(isPrimary = true, isUnique = true)
    public long timestamp;

    @WCDBField(isNotNull = true)
    public byte[] messageEnvelopBytes = new byte[0];

    /**
     * Retry attempt counter. Starts at 0, increments per retry tick attempt
     * that ends in TransientFailure. Row is deleted when it would reach
     * {@code MAX_RETRIES} (see {@code FailedMessageProcessor#bumpRetryOrGiveUp}).
     * No index — cardinality is 0..4, too low for an index to pay back.
     */
    @WCDBField
    public int retryCount = 0;

    /**
     * Wall-clock (ms) of the most recent attempt. New inserts use {@code System.currentTimeMillis()};
     * legacy rows (pre-upgrade) default to 0, which both schedules them as immediately due
     * and trips the first-cycle TTL sweep (see {@code FailedMessageProcessor}).
     * Indexed because the scheduler issues
     * {@code WHERE retryCount < N ORDER BY lastAttemptTime ASC LIMIT M}.
     */
    @WCDBField
    @WCDBIndex
    public long lastAttemptTime = 0;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        FailedMessageModel that = (FailedMessageModel) o;
        return timestamp == that.timestamp
                && retryCount == that.retryCount
                && lastAttemptTime == that.lastAttemptTime
                && Objects.deepEquals(messageEnvelopBytes, that.messageEnvelopBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, Arrays.hashCode(messageEnvelopBytes), retryCount, lastAttemptTime);
    }
}
