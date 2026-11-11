package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding

@WCDBTableCoding
class ResetIdentityKeyModel {

    @WCDBField(isPrimary = true, isUnique = true)
    var uid: String? = null

    @WCDBField
    @WCDBIndex
    var resetTime: Long? = null

    @WCDBField
    var status: Int = 0 

    override fun equals(other: Any?): Boolean {
        if (other !is ResetIdentityKeyModel) return false
        return status == other.status && uid == other.uid && resetTime == other.resetTime
    }

    override fun hashCode(): Int = java.util.Objects.hash(uid, resetTime, status)
}
