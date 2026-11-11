package com.difft.android.chat.group

const val GROUP_ROLE_OWNER = 0
const val GROUP_ROLE_ADMIN = 1
const val GROUP_ROLE_MEMBER = 2





fun canInviteGroupMembers(selfRole: Int, invitationRule: Int?): Boolean = when (invitationRule ?: 2) {
    2 -> true
    0 -> selfRole == GROUP_ROLE_OWNER
    else -> selfRole <= GROUP_ROLE_ADMIN
}


enum class GroupPublishRole(val rawValue: Int) {
    ONLY_OWNER(0),
    ONLY_ADMIN(1),
    ALL(2)
}