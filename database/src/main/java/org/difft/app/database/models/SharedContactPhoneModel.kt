package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class SharedContactPhoneModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    // Faithful 1:1: source field was primitive `long` (non-null column, no null guard
    // in the generated binding), so it stays non-null Long — NOT a boxed FK. #901
    @WCDBIndex
    @WCDBField
    var sharedContactDatabaseId: Long = 0L

    @WCDBField
    var phoneNumberType: Int = 0

    @WCDBField
    var phoneNumber: String? = null

    @WCDBField
    var phoneNumberLabel: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as SharedContactPhoneModel
        return sharedContactDatabaseId == other.sharedContactDatabaseId &&
                phoneNumberType == other.phoneNumberType &&
                phoneNumber == other.phoneNumber &&
                phoneNumberLabel == other.phoneNumberLabel
    }

    override fun hashCode(): Int =
        Objects.hash(sharedContactDatabaseId, phoneNumberType, phoneNumber, phoneNumberLabel)
}
