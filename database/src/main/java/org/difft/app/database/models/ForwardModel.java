package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class ForwardModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex
    @WCDBField
    public long id;

    @WCDBField
    public int type;

    @WCDBField
    public boolean isFromGroup = false;

    @WCDBField
    public String author = "";

    @WCDBField
    public String text = "";

    @WCDBField
    public long serverTimestamp = 0L;

    @WCDBField
    public Long cardModelDatabaseId;

    @WCDBField
    public Long parentForwardModelDatabaseId;

    @WCDBField
    public Long forwardContextDatabaseId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ForwardModel that = (ForwardModel) o;
        return id == that.id && type == that.type && isFromGroup == that.isFromGroup && serverTimestamp == that.serverTimestamp && Objects.equals(author, that.author) && Objects.equals(text, that.text) && Objects.equals(cardModelDatabaseId, that.cardModelDatabaseId) && Objects.equals(parentForwardModelDatabaseId, that.parentForwardModelDatabaseId) && Objects.equals(forwardContextDatabaseId, that.forwardContextDatabaseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, isFromGroup, author, text, serverTimestamp, cardModelDatabaseId, parentForwardModelDatabaseId, forwardContextDatabaseId);
    }
}