package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBTableCoding

@WCDBTableCoding
class GroupCryptoKeysModel {
    @WCDBField(isPrimary = true)
    var gid: String? = null

    @WCDBField
    var rGroup: String? = null

    




    @WCDBField
    var keyVersion: Int = 0

    override fun equals(other: Any?): Boolean {
        if (other == null || javaClass != other.javaClass) return false
        other as GroupCryptoKeysModel
        return gid == other.gid && rGroup == other.rGroup && keyVersion == other.keyVersion
    }

    override fun hashCode(): Int = java.util.Objects.hash(gid, rGroup, keyVersion)
}
