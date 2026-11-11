package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class SharedContactPhoneModel {
    
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    
    
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
