package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class GroupModel {
    
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBIndex(isUnique = true)
    @WCDBField
    var gid: String? = null

    @WCDBField
    var name: String? = null

    @WCDBField
    var messageExpiry: Int? = null

    @WCDBField
    var expireTime: Long? = null

    @WCDBField
    var avatar: String? = null

    @WCDBField
    var status: Int? = null

    @WCDBField
    var invitationRule: Int? = null

    @WCDBField
    var version: Int? = null

    @WCDBField
    var remindCycle: String? = null

    @WCDBField
    var anyoneRemove: Boolean? = null

    @WCDBField
    var rejoin: Boolean? = null

    @WCDBField
    var publishRule: Int? = null

    @WCDBField
    var linkInviteSwitch: Boolean? = false

    @WCDBField
    var privateChat: Boolean? = false

    @WCDBField
    var criticalAlert: Boolean = false

    @WCDBField
    var groupCryptoMode: Int? = null

    @WCDBField
    var encryptedName: String? = null

    @WCDBField
    var encryptedAvatar: String? = null

    @WCDBField
    var groupCryptoKeyVersion: Int? = null

    
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as GroupModel
        return gid == other.gid &&
                name == other.name &&
                messageExpiry == other.messageExpiry &&
                expireTime == other.expireTime &&
                avatar == other.avatar &&
                status == other.status &&
                invitationRule == other.invitationRule &&
                version == other.version &&
                remindCycle == other.remindCycle &&
                anyoneRemove == other.anyoneRemove &&
                rejoin == other.rejoin &&
                publishRule == other.publishRule &&
                linkInviteSwitch == other.linkInviteSwitch &&
                privateChat == other.privateChat &&
                criticalAlert == other.criticalAlert &&
                groupCryptoMode == other.groupCryptoMode &&
                encryptedName == other.encryptedName &&
                encryptedAvatar == other.encryptedAvatar &&
                groupCryptoKeyVersion == other.groupCryptoKeyVersion
    }

    
    override fun hashCode(): Int = Objects.hash(
        gid,
        name,
        messageExpiry,
        expireTime,
        avatar,
        status,
        invitationRule,
        version,
        remindCycle,
        anyoneRemove,
        rejoin,
        publishRule,
        linkInviteSwitch,
        privateChat,
        criticalAlert,
        groupCryptoMode,
        encryptedName,
        encryptedAvatar,
        groupCryptoKeyVersion
    )
}
