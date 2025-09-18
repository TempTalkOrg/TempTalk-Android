package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class DraftModel {

    @WCDBField(isPrimary = true, isUnique = true)
    public String roomId;

    @WCDBField
    public String draftJson;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        DraftModel that = (DraftModel) o;
        return Objects.equals(roomId, that.roomId) && Objects.equals(draftJson, that.draftJson);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, draftJson);
    }
}