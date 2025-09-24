package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class MentionModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBField
    @WCDBIndex
    public String messageId;

    @WCDBField
    public Long forwardModelDatabaseId;

    @WCDBField
    public int start;

    @WCDBField
    public int length;

    @WCDBField
    public String uid;

    @WCDBField
    public int type;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MentionModel that = (MentionModel) o;
        return start == that.start && length == that.length && type == that.type && Objects.equals(messageId, that.messageId) && Objects.equals(forwardModelDatabaseId, that.forwardModelDatabaseId) && Objects.equals(uid, that.uid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, forwardModelDatabaseId, start, length, uid, type);
    }
}