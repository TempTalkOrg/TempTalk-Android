package com.difft.android.websocket.api.messages

enum class GroupNotifyDetailType(val value: Int) {
    CreateGroup(0),
    JoinGroup(1),
    LeaveGroup(2), //msgExtra中不带会话id，且不计入sequenceId
    InviteJoinGroup(3),
    KickoutGroup(4), //msgExtra中不带会话id，且不计入sequenceId
    DismissGroup(5),
    GroupNameChange(6),
    GroupAvatarChange(7),
    GroupMsgExpiryChange(8),
    GroupAddAdmin(9),
    GroupDeleteAdmin(10),
    GroupMemberInfoChange(11),
    GroupOwnerChange(12),
    GroupAddAnnnouncement(13),
    GroupUpdateAnnnouncement(14),
    GroupDeleteAnnnouncement(15),
    GroupOtherChange(16),
    GroupSelfInfoChange(17),//msgExtra中不带会话id，且不计入sequenceId
    GroupAddPin(18),
    GroupDeletePin(19),
    GroupInvitationRuleChange(20),
    GroupRemindChange(21),
    GroupRemind(22),
    GroupRapidRoleChange(23),
    GroupAnyoneRemoveChange(24),
    GroupRejoinChange(25),
    GroupExtChange(26),
    GroupPublishRuleChange(27),
    GroupInactive(28),
    GroupLinkJoin(29),
    GroupMaxSizeChange(30),
    GroupAnyoneChangeNameChange(31),
    AnyoneChangeAutoClearChange(32),
    AutoClearChange(33),
    KickoutAutoClear(34),//msgExtra中不带会话id，且不计入sequenceId
    Destroy(35),
    PrivilegeConfidential(36),
    WeblinkInviteJoin(37),
    WeblinkInviteSwitchChange(38),
    Archive(39),
    GroupCostChange(40),
    PrivateChatChange(41),
    GroupAccountInvalid(999);

    companion object {
        fun from(findValue: Int): GroupNotifyDetailType =
            values().first { it.value == findValue }
    }
}