package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class TranslateModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex(isUnique = true)
    @WCDBField
    public String messageId;

    /**
     *     Invisible(0),
     *     Translating(1),
     *     ShowCN(2),
     *     ShowEN(3);
     */
    @WCDBField
    public int translateStatus;

    @WCDBField
    public String translatedContentCN;

    @WCDBField
    public String translatedContentEN;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TranslateModel that = (TranslateModel) o;
        return databaseId == that.databaseId && translateStatus == that.translateStatus && Objects.equals(messageId, that.messageId) && Objects.equals(translatedContentCN, that.translatedContentCN) && Objects.equals(translatedContentEN, that.translatedContentEN);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseId, messageId, translateStatus, translatedContentCN, translatedContentEN);
    }
}
