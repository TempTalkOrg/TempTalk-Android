package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class ForwardModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex
    @WCDBField
    var id: Long = 0L

    @WCDBField
    var type: Int = 0

    @WCDBField
    var isFromGroup: Boolean = false

    @WCDBField
    var author: String = ""

    @WCDBField
    var text: String = ""

    @WCDBField
    var serverTimestamp: Long = 0L

    @WCDBField
    var parentForwardModelDatabaseId: Long? = null

    @WCDBField
    var forwardContextDatabaseId: Long? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as ForwardModel
        return id == other.id &&
                type == other.type &&
                isFromGroup == other.isFromGroup &&
                serverTimestamp == other.serverTimestamp &&
                author == other.author &&
                text == other.text &&
                parentForwardModelDatabaseId == other.parentForwardModelDatabaseId &&
                forwardContextDatabaseId == other.forwardContextDatabaseId
    }

    override fun hashCode(): Int =
        Objects.hash(
            id,
            type,
            isFromGroup,
            author,
            text,
            serverTimestamp,
            parentForwardModelDatabaseId,
            forwardContextDatabaseId
        )
}
