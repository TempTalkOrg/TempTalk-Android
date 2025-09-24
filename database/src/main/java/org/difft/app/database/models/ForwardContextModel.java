package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class ForwardContextModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBField
    public boolean isFromGroup = false;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ForwardContextModel that = (ForwardContextModel) o;
        return isFromGroup == that.isFromGroup;
    }

    @Override
    public int hashCode() {
        return Objects.hash(isFromGroup);
    }
}