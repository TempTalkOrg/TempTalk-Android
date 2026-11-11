package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects















@WCDBTableCoding
class PendingRemovalContactModel {

    
    @WCDBField(isPrimary = true, isNotNull = true)
    var uid: String = ""

    
    @WCDBField
    @WCDBIndex
    var expireAt: Long = 0L

    
    @WCDBField
    var reason: Int = 0

    
    @WCDBField
    var deleteTime: Long = 0L

    
    @WCDBField
    var snapshotJson: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as PendingRemovalContactModel
        return uid == other.uid &&
                expireAt == other.expireAt &&
                reason == other.reason &&
                deleteTime == other.deleteTime &&
                snapshotJson == other.snapshotJson
    }

    override fun hashCode(): Int =
        Objects.hash(uid, expireAt, reason, deleteTime, snapshotJson)
}
