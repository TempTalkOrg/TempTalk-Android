package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects





@WCDBTableCoding
class FavoriteGifModel {
    
    @WCDBField(isPrimary = true)
    var fileHash: String = ""

    @WCDBField
    var attachmentId: String? = null

    @WCDBField
    var authorizeId: Long = 0L

    
    @WCDBField
    var encKey: String? = null

    
    @WCDBField
    var digest: String? = null

    @WCDBField
    var contentType: String = "image/gif"

    @WCDBField
    var width: Int = 0

    @WCDBField
    var height: Int = 0

    
    @WCDBField
    var size: Int = 0

    
    @WCDBField
    var addedListVersion: Long = 0L

    
    @WCDBField
    var pending: Boolean = false

    




    @WCDBField
    var sourceUrl: String? = null

    





    @WCDBField
    var pendingSince: Long = 0L

    






    @WCDBField
    var pendingSourceJson: String? = null

    







    @WCDBField
    var pendingRemoval: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as FavoriteGifModel
        return fileHash == other.fileHash
    }

    override fun hashCode(): Int = Objects.hash(fileHash)
}
