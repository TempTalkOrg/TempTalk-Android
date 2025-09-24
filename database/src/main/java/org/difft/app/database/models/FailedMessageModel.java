package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Arrays;
import java.util.Objects;

@WCDBTableCoding
public class FailedMessageModel {

    @WCDBField(isPrimary = true, isUnique = true)
    public long timestamp;

    @WCDBField(isNotNull = true)
    public byte[] messageEnvelopBytes = new byte[0];

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        FailedMessageModel that = (FailedMessageModel) o;
        return timestamp == that.timestamp && Objects.deepEquals(messageEnvelopBytes, that.messageEnvelopBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, Arrays.hashCode(messageEnvelopBytes));
    }
}