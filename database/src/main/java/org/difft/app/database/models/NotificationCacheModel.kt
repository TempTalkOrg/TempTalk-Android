package org.difft.app.database.models

import com.tencent.wcdb.MultiPrimary
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding





@WCDBTableCoding(multiPrimaries = [MultiPrimary(columns = ["conversationId", "timestamp"])])
class NotificationCacheModel {

    @WCDBField(isNotNull = true)
    var conversationId: String = ""

    @WCDBField(isNotNull = true)
    var timestamp: Long = 0

    @WCDBField(isNotNull = true)
    var content: String = ""

    @WCDBField(isNotNull = true)
    var personKey: String = ""

    
    @WCDBField(isNotNull = true)
    var personName: String = ""

    
    @WCDBField(isNotNull = true)
    @WCDBIndex
    var createdAt: Long = 0
}
