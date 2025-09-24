package org.difft.app.database.models;

import com.tencent.wcdb.MultiUnique;
import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding(multiUnique = @MultiUnique(columns = {"messageId", "emoji", "uid"}))
public class ReactionModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex
    @WCDBField
    public String messageId;

    @WCDBField(isNotNull = true)
    public String emoji;

    @WCDBField
    public String uid;

    @WCDBField
    public long timeStamp;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReactionModel that = (ReactionModel) o;
        return timeStamp == that.timeStamp && Objects.equals(messageId, that.messageId) && Objects.equals(emoji, that.emoji) && Objects.equals(uid, that.uid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, emoji, uid, timeStamp);
    }
}