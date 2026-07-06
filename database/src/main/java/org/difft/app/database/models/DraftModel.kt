package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBTableCoding

@WCDBTableCoding
class DraftModel {

    @WCDBField(isPrimary = true, isUnique = true)
    var roomId: String? = null

    @WCDBField
    var draftJson: String? = null

    @WCDBField
    var updatedAt: Long = 0

    override fun equals(other: Any?): Boolean {
        if (other == null || javaClass != other.javaClass) return false
        other as DraftModel
        return updatedAt == other.updatedAt && roomId == other.roomId && draftJson == other.draftJson
    }

    override fun hashCode(): Int = java.util.Objects.hash(roomId, draftJson, updatedAt)
}
