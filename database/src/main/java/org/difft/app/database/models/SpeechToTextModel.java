package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;
import java.util.Objects;


@WCDBTableCoding
public class SpeechToTextModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBIndex(isUnique = true)
    @WCDBField
    public String messageId;

    /**
     *     Invisible(0),
     *     Converting(1),
     *     Show(2),
     */
    @WCDBField
    public int convertStatus;

    @WCDBField
    public String speechToTextContent;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SpeechToTextModel that = (SpeechToTextModel) o;
        return databaseId == that.databaseId && convertStatus == that.convertStatus && Objects.equals(messageId, that.messageId) && Objects.equals(speechToTextContent, that.speechToTextContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseId, messageId, convertStatus, speechToTextContent);
    }
}
