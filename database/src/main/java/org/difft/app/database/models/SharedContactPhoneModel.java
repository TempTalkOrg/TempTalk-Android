package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class SharedContactPhoneModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex
    @WCDBField
    public long sharedContactDatabaseId;

    @WCDBField
    public int phoneNumberType;

    @WCDBField
    public String phoneNumber;

    @WCDBField
    public String phoneNumberLabel;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SharedContactPhoneModel that = (SharedContactPhoneModel) o;
        return sharedContactDatabaseId == that.sharedContactDatabaseId && phoneNumberType == that.phoneNumberType && Objects.equals(phoneNumber, that.phoneNumber) && Objects.equals(phoneNumberLabel, that.phoneNumberLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sharedContactDatabaseId, phoneNumberType, phoneNumber, phoneNumberLabel);
    }
}