package com.difft.android.network.responses

data class MeetingDetailResponse(
    val name: String? = null,
    val groupMeetingId: Int? = null,
    val userEvent: List<UserEvent>,
    val nonParticipants: List<String>? = null,
    val groupInfo: GroupInfo? = null,
    val permissions: MeetingDetailsPermissions? = null
)

data class UserEvent(
    val account: String,
    val event: String,
    val timestamp: Long,
    var userName: String = "",
    var email: String? = null,
    var avatarUrl: String? = null,
    var avatarEncKey: String? = null
)

data class GroupInfo(
    val exist: Boolean,
    val gid: String?,
    val inviteCode: String?
)

data class MeetingDetailsPermissions(
    val joinGroup: String
)