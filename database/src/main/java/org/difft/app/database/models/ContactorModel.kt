package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class ContactorModel {
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBField(isNotNull = true)
    @WCDBIndex(isUnique = true)
    var id: String = ""

    @WCDBField
    var name: String? = null

    @WCDBField
    var email: String? = null

    @WCDBField
    var avatar: String? = null

    @WCDBField
    var meetingVersion: Int = 1

    @WCDBField
    var publicName: String? = null

    @WCDBField
    var timeZone: String? = null

    @WCDBField
    var remark: String? = null

    @WCDBField
    var remarkAvatar: String? = null

    @WCDBField
    var joinedAt: String? = null

    @WCDBField
    var sourceDescribe: String? = null

    @WCDBField
    var findyouDescribe: String? = null

    var groupMemberContactor: GroupMemberContactorModel? = null

    @WCDBField
    var customUid: String? = null

    @WCDBField
    var publicAccountType: Int = PublicAccountType.NORMAL   

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as ContactorModel
        
        return meetingVersion == other.meetingVersion &&
                id == other.id &&
                name == other.name &&
                email == other.email &&
                avatar == other.avatar &&
                publicName == other.publicName &&
                timeZone == other.timeZone &&
                remark == other.remark &&
                remarkAvatar == other.remarkAvatar &&
                joinedAt == other.joinedAt &&
                sourceDescribe == other.sourceDescribe &&
                findyouDescribe == other.findyouDescribe &&
                groupMemberContactor == other.groupMemberContactor &&
                customUid == other.customUid &&
                publicAccountType == other.publicAccountType
    }

    
    override fun hashCode(): Int = Objects.hash(
        id, name, email, avatar, meetingVersion, publicName, timeZone, remark,
        remarkAvatar, joinedAt, sourceDescribe, findyouDescribe, groupMemberContactor, customUid,
        publicAccountType
    )
}
