package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class CardModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex
    @WCDBField(isNotNull = true)
    public String uniqueId;

    @WCDBField(isNotNull = true)
    public String cardId;

    @WCDBField(isNotNull = true)
    public String appId;

    @WCDBField
    public int version;

    @WCDBField
    public String creator;

    @WCDBField
    public long timestamp;

    @WCDBField
    public String content;

    @WCDBField
    public int contentType;

    @WCDBField
    public int type;

    @WCDBField
    public boolean fixedWidth;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CardModel cardModel = (CardModel) o;
        return version == cardModel.version && timestamp == cardModel.timestamp && contentType == cardModel.contentType && type == cardModel.type && fixedWidth == cardModel.fixedWidth && Objects.equals(uniqueId, cardModel.uniqueId) && Objects.equals(cardId, cardModel.cardId) && Objects.equals(appId, cardModel.appId) && Objects.equals(creator, cardModel.creator) && Objects.equals(content, cardModel.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uniqueId, cardId, appId, version, creator, timestamp, content, contentType, type, fixedWidth);
    }
}