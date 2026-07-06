package org.difft.app.database.models

import com.tencent.wcdb.MultiPrimary
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding(multiPrimaries = [MultiPrimary(columns = ["roomId", "uid"])])
class ReadInfoModel {

    @WCDBIndex
    @WCDBField(isNotNull = true)
    var roomId: String = ""

    @WCDBField(isNotNull = true)
    var uid: String = ""

    @WCDBField
    var readPosition: Long = 0 //读的位置

    override fun equals(other: Any?): Boolean {
        if (other == null || javaClass != other.javaClass) return false
        other as ReadInfoModel
        return readPosition == other.readPosition && roomId == other.roomId && uid == other.uid
    }

    override fun hashCode(): Int = Objects.hash(roomId, uid, readPosition)
}
