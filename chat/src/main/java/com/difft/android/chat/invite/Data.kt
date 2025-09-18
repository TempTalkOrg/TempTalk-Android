package com.difft.android.chat.invite

data class GetInviteCodeRequest(
    val regenerate: Int?  //可选；重新生成时，置为1；默认置0
)

data class GetInviteCodeResponse(
    val inviteCode: String?,
    val randomCode: String?,
    val randomCodeTTL: Int,
    val randomCodeExpiration: Int,
    val inviteLink: String?
)

data class QueryInviteCodeRequest(
    val inviteCode: String
)

data class QueryInviteCodeResponse(
    val uid: String?,
    val name: String?,
    val avatarContent: String?,
    val avatar: String?,
    val joinedAt: String?
)