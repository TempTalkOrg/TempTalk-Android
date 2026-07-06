package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class MentionModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBField
    @WCDBIndex
    var messageId: String? = null

    @WCDBField
    var forwardModelDatabaseId: Long? = null

    @WCDBField
    var start: Int = 0

    @WCDBField
    var length: Int = 0

    @WCDBField
    var uid: String? = null

    @WCDBField
    var type: Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as MentionModel
        return start == other.start &&
                length == other.length &&
                type == other.type &&
                messageId == other.messageId &&
                forwardModelDatabaseId == other.forwardModelDatabaseId &&
                uid == other.uid
    }

    override fun hashCode(): Int =
        Objects.hash(messageId, forwardModelDatabaseId, start, length, uid, type)
}
