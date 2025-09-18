package org.difft.app.database.models;

import com.tencent.wcdb.MultiPrimary;
import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding(multiPrimaries = @MultiPrimary(columns = {"roomId", "uid"}))
public class ReadInfoModel {

    @WCDBIndex
    @WCDBField(isNotNull = true)
    public String roomId;

    @WCDBField(isNotNull = true)
    public String uid;

    @WCDBField
    public long readPosition; //读的位置

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ReadInfoModel that = (ReadInfoModel) o;
        return readPosition == that.readPosition && Objects.equals(roomId, that.roomId) && Objects.equals(uid, that.uid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, uid, readPosition);
    }
}