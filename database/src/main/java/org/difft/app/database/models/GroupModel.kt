package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class GroupModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
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

    // exclude databaseId: rowid must not affect content equality (faithful to the
    // original Java equals/hashCode, which already excluded it), #901
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as GroupModel
        return gid == other.gid &&
                name == other.name &&
                messageExpiry == other.messageExpiry &&
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

    // exclude databaseId: rowid must not affect content equality, #901
    override fun hashCode(): Int = Objects.hash(
        gid,
        name,
        messageExpiry,
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
