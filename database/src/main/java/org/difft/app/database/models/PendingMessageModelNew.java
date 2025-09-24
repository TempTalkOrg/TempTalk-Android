package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Arrays;
import java.util.Objects;

@WCDBTableCoding
public class PendingMessageModelNew {

    @WCDBField(isPrimary = true, isUnique = true)
    public String messageId;

    @WCDBField
    public long originalMessageTimeStamp;

    @WCDBField(isNotNull = true)
    public byte[] messageEnvelopBytes = new byte[0];

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PendingMessageModelNew that = (PendingMessageModelNew) o;
        return Objects.equals(messageId, that.messageId) && Objects.equals(originalMessageTimeStamp, that.originalMessageTimeStamp) && Objects.deepEquals(messageEnvelopBytes, that.messageEnvelopBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, originalMessageTimeStamp, Arrays.hashCode(messageEnvelopBytes));
    }
}