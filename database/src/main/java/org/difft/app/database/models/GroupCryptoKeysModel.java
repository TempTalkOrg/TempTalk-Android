package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class GroupCryptoKeysModel {
    @WCDBField(isPrimary = true)
    public String gid;

    @WCDBField
    public String rGroup;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        GroupCryptoKeysModel that = (GroupCryptoKeysModel) o;
        return Objects.equals(gid, that.gid) && Objects.equals(rGroup, that.rGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gid, rGroup);
    }
}
