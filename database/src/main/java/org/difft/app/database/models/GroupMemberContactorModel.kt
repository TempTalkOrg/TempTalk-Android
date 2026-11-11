package org.difft.app.database.models

import com.tencent.wcdb.MultiUnique
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding(multiUnique = [MultiUnique(columns = ["gid", "id"])])
class GroupMemberContactorModel {
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex
    @WCDBField
    var id: String? = null

    @WCDBField(isNotNull = true)
    @WCDBIndex
    var gid: String = ""

    @WCDBField
    var displayName: String? = null 

    @WCDBField
    var notification: Int? = null

    @WCDBField
    var rapidRole: Int? = null

    @WCDBField
    var remark: String? = null

    @WCDBField
    var remarkAvatar: String? = null

    @WCDBField
    var useGlobal: Boolean? = false

    @WCDBField
    var extId: Int? = null

    
    @WCDBField
    var groupRole: Int? = null

    @WCDBField
    var uidSignature: String? = null

    







    @WCDBField
    var signatureVerify: Boolean? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as GroupMemberContactorModel
        
        return id == other.id &&
                gid == other.gid &&
                displayName == other.displayName &&
                notification == other.notification &&
                rapidRole == other.rapidRole &&
                remark == other.remark &&
                remarkAvatar == other.remarkAvatar &&
                useGlobal == other.useGlobal &&
                extId == other.extId &&
                groupRole == other.groupRole &&
                uidSignature == other.uidSignature &&
                signatureVerify == other.signatureVerify
    }

    
    override fun hashCode(): Int = Objects.hash(
        id, gid, displayName, notification, rapidRole, remark, remarkAvatar,
        useGlobal, extId, groupRole, uidSignature, signatureVerify
    )
}
