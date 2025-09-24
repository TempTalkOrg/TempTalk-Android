package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class SharedContactModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex
    @WCDBField
    public String messageId;

    @WCDBField
    public String givenName;

    @WCDBField
    public String familyName;

    @WCDBField
    public String namePrefix;

    @WCDBField
    public String nameSuffix;

    @WCDBField
    public String middleName;

    @WCDBField
    public String displayName;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SharedContactModel that = (SharedContactModel) o;
        return Objects.equals(messageId, that.messageId) && Objects.equals(givenName, that.givenName) && Objects.equals(familyName, that.familyName) && Objects.equals(namePrefix, that.namePrefix) && Objects.equals(nameSuffix, that.nameSuffix) && Objects.equals(middleName, that.middleName) && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, givenName, familyName, namePrefix, nameSuffix, middleName, displayName);
    }
}