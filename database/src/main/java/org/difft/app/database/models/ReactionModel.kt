package org.difft.app.database.models

import com.tencent.wcdb.MultiUnique
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding(multiUnique = [MultiUnique(columns = ["messageId", "emoji", "uid"])])
class ReactionModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex
    @WCDBField
    var messageId: String? = null

    @WCDBField(isNotNull = true)
    var emoji: String = ""

    @WCDBField
    var uid: String? = null

    @WCDBField
    var timeStamp: Long = 0L

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as ReactionModel
        return timeStamp == other.timeStamp &&
                messageId == other.messageId &&
                emoji == other.emoji &&
                uid == other.uid
    }

    override fun hashCode(): Int = Objects.hash(messageId, emoji, uid, timeStamp)
}
