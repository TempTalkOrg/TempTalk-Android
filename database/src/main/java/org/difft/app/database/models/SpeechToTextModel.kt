package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class SpeechToTextModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex(isUnique = true)
    @WCDBField
    var messageId: String? = null

    /**
     *     Invisible(0),
     *     Converting(1),
     *     Show(2),
     */
    @WCDBField
    var convertStatus: Int = 0

    @WCDBField
    var speechToTextContent: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as SpeechToTextModel
        // exclude databaseId: rowid must not affect content equality, #901
        return convertStatus == other.convertStatus &&
                messageId == other.messageId &&
                speechToTextContent == other.speechToTextContent
    }

    // exclude databaseId: rowid must not affect content equality, #901
    override fun hashCode(): Int = Objects.hash(messageId, convertStatus, speechToTextContent)
}
