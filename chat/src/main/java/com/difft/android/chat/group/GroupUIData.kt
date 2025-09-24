package com.difft.android.chat.group

import org.difft.app.database.models.GroupMemberContactorModel

data class GroupUIData(
    var gid: String = "",
    var name: String? = null,
    var messageExpiry: Int? = null,
    var avatar: String? = null,
    var status: Int? = null,
    var invitationRule: Int? = null,
    var version: Int? = null,
    var remindCycle: String? = null,
    var anyoneRemove: Boolean? = null,
    var rejoin: Boolean? = null,
    var publishRule: Int? = null,
    var linkInviteSwitch: Boolean? = false,
    var privateChat: Boolean = false,
    val members: List<GroupMemberContactorModel>
)