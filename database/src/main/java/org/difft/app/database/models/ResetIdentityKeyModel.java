package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class ResetIdentityKeyModel {

    @WCDBField(isPrimary = true, isUnique = true)
    public String uid;

    @WCDBField
    @WCDBIndex
    public Long resetTime;

    @WCDBField
    public int status; //消息清理状态 0: not cleared, 1: cleared

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ResetIdentityKeyModel that)) return false;
        return status == that.status && Objects.equals(uid, that.uid) && Objects.equals(resetTime, that.resetTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, resetTime, status);
    }
}