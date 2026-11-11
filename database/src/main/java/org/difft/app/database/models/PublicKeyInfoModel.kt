package org.difft.app.database.models

import com.tencent.wcdb.WCDBDefault
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects














@WCDBTableCoding
class PublicKeyInfoModel {

    
    @WCDBField(isPrimary = true, isNotNull = true)
    var uid: String = ""

    




    @WCDBField(isNotNull = true)
    var identityKey: String = ""

    
    @WCDBField(isNotNull = true)
    @WCDBDefault(intValue = 0)
    var registrationId: Int = 0

    









    @WCDBField(isNotNull = true)
    @WCDBDefault(intValue = 0)
    var resetIdentityKeyTime: Long = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as PublicKeyInfoModel
        return registrationId == other.registrationId &&
                resetIdentityKeyTime == other.resetIdentityKeyTime &&
                uid == other.uid &&
                identityKey == other.identityKey
    }

    override fun hashCode(): Int =
        Objects.hash(uid, identityKey, registrationId, resetIdentityKeyTime)

    
    override fun toString(): String =
        "PublicKeyInfoModel{" +
                "uid='" + uid + '\'' +
                ", registrationId=" + registrationId +
                ", resetIdentityKeyTime=" + resetIdentityKeyTime +
                '}'
}
