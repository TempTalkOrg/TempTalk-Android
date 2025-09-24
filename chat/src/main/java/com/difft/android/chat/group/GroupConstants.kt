package com.difft.android.chat.group

const val GROUP_ROLE_OWNER = 0
const val GROUP_ROLE_ADMIN = 1
const val GROUP_ROLE_MEMBER = 2

//0 - 仅群主可发言 1 - 仅管理员可发言 2 - 任何人可发言
enum class GroupPublishRole(val rawValue: Int) {
    ONLY_OWNER(0),
    ONLY_ADMIN(1),
    ALL(2)
}