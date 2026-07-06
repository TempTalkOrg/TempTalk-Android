package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class SharedContactModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex
    @WCDBField
    var messageId: String? = null

    @WCDBField
    var givenName: String? = null

    @WCDBField
    var familyName: String? = null

    @WCDBField
    var namePrefix: String? = null

    @WCDBField
    var nameSuffix: String? = null

    @WCDBField
    var middleName: String? = null

    @WCDBField
    var displayName: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as SharedContactModel
        return messageId == other.messageId &&
                givenName == other.givenName &&
                familyName == other.familyName &&
                namePrefix == other.namePrefix &&
                nameSuffix == other.nameSuffix &&
                middleName == other.middleName &&
                displayName == other.displayName
    }

    override fun hashCode(): Int =
        Objects.hash(messageId, givenName, familyName, namePrefix, nameSuffix, middleName, displayName)
}
