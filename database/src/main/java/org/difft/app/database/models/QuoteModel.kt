package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class QuoteModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBField
    @WCDBIndex
    var id: Long = 0L

    @WCDBField
    var author: String = ""

    @WCDBField
    var text: String = ""

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as QuoteModel
        return id == other.id && author == other.author && text == other.text
    }

    override fun hashCode(): Int = Objects.hash(id, author, text)

    override fun toString(): String =
        "QuoteModel{" +
                "databaseId=" + databaseId +
                ", id=" + id +
                ", author='" + author + '\'' +
                ", text='" + text + '\'' +
                '}'
}
