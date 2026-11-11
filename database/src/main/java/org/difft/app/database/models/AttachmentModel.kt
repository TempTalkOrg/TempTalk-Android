package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Arrays
import java.util.Objects

@WCDBTableCoding
class AttachmentModel {
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex
    @WCDBField
    var id: String? = null

    @WCDBIndex
    @WCDBField
    var messageId: String? = null

    





    @WCDBIndex
    @WCDBField
    var localId: String? = null

    
    @WCDBField
    @WCDBIndex
    var forwardModelDatabaseId: Long? = null

    
    @WCDBField
    @WCDBIndex
    var quoteModelDatabaseId: Long? = null

    
    
    
    @WCDBIndex
    @WCDBField
    var authorityId: Long? = null

    @WCDBField
    var contentType: String? = null

    @WCDBField
    var key: ByteArray? = ByteArray(0)

    @WCDBField
    var size: Int = 0

    @WCDBField
    var thumbnail: ByteArray? = ByteArray(0)

    @WCDBField
    var digest: ByteArray? = ByteArray(0)

    @WCDBField
    var fileName: String? = ""

    @WCDBField
    var flags: Int = 0

    @WCDBField
    var width: Int = 0

    @WCDBField
    var height: Int = 0

    
    

    @WCDBField
    var status: Int = 0

    
    @WCDBField
    var totalTime: Long? = null 

    @WCDBField
    var amplitudes: String? = null 

    



    @WCDBField
    var videoProbeState: Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as AttachmentModel
        return size == other.size && flags == other.flags && width == other.width &&
                height == other.height && status == other.status &&
                videoProbeState == other.videoProbeState &&
                id == other.id && messageId == other.messageId &&
                forwardModelDatabaseId == other.forwardModelDatabaseId &&
                quoteModelDatabaseId == other.quoteModelDatabaseId &&
                authorityId == other.authorityId && contentType == other.contentType &&
                Objects.deepEquals(key, other.key) &&
                Objects.deepEquals(thumbnail, other.thumbnail) &&
                Objects.deepEquals(digest, other.digest) &&
                fileName == other.fileName
    }

    override fun hashCode(): Int =
        Objects.hash(
            id, messageId, forwardModelDatabaseId, quoteModelDatabaseId, authorityId,
            contentType, Arrays.hashCode(key), size, Arrays.hashCode(thumbnail),
            Arrays.hashCode(digest), fileName, flags, width, height, status, videoProbeState
        )
}
