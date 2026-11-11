package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class TranslateModel {
    
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex(isUnique = true)
    @WCDBField
    var messageId: String? = null

    





    @WCDBField
    var translateStatus: Int = 0

    @WCDBField
    var translatedContentCN: String? = null

    @WCDBField
    var translatedContentEN: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as TranslateModel
        
        return translateStatus == other.translateStatus &&
                messageId == other.messageId &&
                translatedContentCN == other.translatedContentCN &&
                translatedContentEN == other.translatedContentEN
    }

    
    override fun hashCode(): Int =
        Objects.hash(messageId, translateStatus, translatedContentCN, translatedContentEN)
}
