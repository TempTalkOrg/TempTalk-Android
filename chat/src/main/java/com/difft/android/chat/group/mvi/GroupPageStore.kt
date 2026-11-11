package com.difft.android.chat.group.mvi

import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getFirstLetter
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.chat.group.GroupMemberModel
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import org.difft.app.database.convertToContactorModels
import org.difft.app.database.members
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.wcdb
import javax.inject.Inject


class GroupPageStore @Inject constructor() {
    data class Snapshot(
        val gid: String,
        val status: Int,
        val invitationRule: Int,
        val publishRule: Int,
        val criticalAlert: Boolean,
        val anyoneRemove: Boolean,
        val encrypted: Boolean,
        val members: List<GroupMemberModel>,
    )

    fun snapshot(gid: String): Snapshot? {
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid)) ?: return null
        return Snapshot(gid, group.status ?: 0, group.invitationRule ?: 2, group.publishRule ?: 2,
            group.criticalAlert == true, group.anyoneRemove == true,
            group.groupCryptoMode != null && group.groupCryptoMode != 0,
            group.members.convertToContactorModels().map { it.toGroupMemberRow() })
    }

    fun contacts(): List<GroupMemberModel> = wcdb.contactor.allObjects.map { it.toGroupMemberRow() }
}


internal fun ContactorModel.toGroupMemberRow(): GroupMemberModel {
    val avatar = getEffectiveAvatarJson()?.getContactAvatarData()
    val name = getDisplayNameForUI()
    return GroupMemberModel(name, id, avatar?.getContactAvatarUrl(), avatar?.encKey,
        name.getFirstLetter(), groupMemberContactor?.groupRole ?: GROUP_ROLE_MEMBER,
        letterName = getDisplayNameWithoutRemarkForUI(),
        searchNames = listOfNotNull(remark, publicName, this.name).filter { it.isNotBlank() })
}
