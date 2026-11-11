package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class ForwardContextModel {
    
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBField
    var isFromGroup: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as ForwardContextModel
        return isFromGroup == other.isFromGroup
    }

    override fun hashCode(): Int = Objects.hash(isFromGroup)
}
