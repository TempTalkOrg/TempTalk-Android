package com.difft.android.call.data

/**
 * 会议邀请成员数据模型
 * 用于在call模块内部表示邀请的成员信息
 */
data class InviteMember(
    val uid: String,
    val name: String,
    val avatarUrl: String? = null,
    val avatarEncKey: String? = null,
    val sortLetter: String = "#"
)

