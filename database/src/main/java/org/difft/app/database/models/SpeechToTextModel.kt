package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class SpeechToTextModel {
    
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex(isUnique = true)
    @WCDBField
    var messageId: String? = null

    




    @WCDBField
    var convertStatus: Int = 0

    @WCDBField
    var speechToTextContent: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as SpeechToTextModel
        
        return convertStatus == other.convertStatus &&
                messageId == other.messageId &&
                speechToTextContent == other.speechToTextContent
    }

    
    override fun hashCode(): Int = Objects.hash(messageId, convertStatus, speechToTextContent)
}
