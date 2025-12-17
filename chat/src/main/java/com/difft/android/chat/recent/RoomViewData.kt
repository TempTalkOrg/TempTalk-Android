package com.difft.android.chat.recent

import com.difft.android.base.call.CallData
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE

data class RoomViewData(
    val roomId: String,
    val type: Type = Type.OneOnOne,
    var roomName: CharSequence? = null,
    val roomAvatarJson: String? = null, //currently it only use for group
    val lastDisplayContent: CharSequence? = null,
    var lastActiveTime: Long? = 0L,
    val lastActiveTimeText: String = "",
    val unreadMessageNum: Int = 0,
    val muteStatus: Int = 0, //   MUTED(1), UNMUTED(0);
    val pinnedTime: Long? = 0L,
    val mentionType: Int = MENTIONS_TYPE_NONE,
    val criticalAlertType: Int = CRITICAL_ALERT_TYPE_NONE, // Critical Alert 类型
    val messageExpiry: Long? = null, //消息过期时间
    var isInstantCall: Boolean = false,
    var isLiveStream: Boolean = false,
    var callData: CallData? = null,
    val draftPreview: String? = null,
    val groupMembersNumber: Int = 0, //群成员数量
) {
    sealed class Type {
        data object OneOnOne : Type()
        data object Group : Type()
    }

    val isMuted: Boolean
        get() = muteStatus == MuteStatus.MUTED.value

    val isPinned: Boolean
        get() = pinnedTime != null && pinnedTime > 0

}


enum class MuteStatus(val value: Int) {
    MUTED(1),
    UNMUTED(0);
}

//val RoomViewData.group: GroupModel?
//    get() = wcdb.group.getFirstObject(DBGroupModel.gid.eq(roomId))

//val RoomViewData.contactor: ContactorModel?
//    get() = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(roomId))

data class CallInfo(
    var isJoinEnable: Boolean = false,
    var roomId: String? = null,
)