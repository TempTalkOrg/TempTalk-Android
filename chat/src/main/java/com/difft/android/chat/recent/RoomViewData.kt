package com.difft.android.chat.recent

import com.difft.android.base.call.CallData
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE

data class RoomViewData(
    val roomId: String,
    val type: Type = Type.OneOnOne,
    var roomName: CharSequence? = null,
    val roomAvatarJson: String? = null, 
    
    val remarkAvatarJson: String? = null,
    val lastDisplayContent: CharSequence? = null,
    var lastActiveTime: Long? = 0L,
    val lastActiveTimeText: String = "",
    val unreadMessageNum: Int = 0,
    val muteStatus: Int = 0, 
    val pinnedTime: Long? = 0L,
    val mentionType: Int = MENTIONS_TYPE_NONE,
    val criticalAlertType: Int = CRITICAL_ALERT_TYPE_NONE,
    







    val sendStatus: Int = ROOM_SEND_STATUS_NONE,
    




    val sendingStatus: Int = ROOM_SENDING_STATUS_NONE,
    val messageExpiry: Long? = null,
    var isInstantCall: Boolean = false,
    var isLiveStream: Boolean = false,
    var callData: CallData? = null,
    val draftPreview: String? = null,
    val sortTime: Long = 0L,
    val groupMembersNumber: Int = 0,
    





    val isManualUnread: Boolean = false,
    



    val effectiveMuted: Boolean? = null,
    
    val focusAction: FocusRowAction = FocusRowAction.NONE,
    
    val focusPreviewLines: List<String> = emptyList(),
    
    val isSynthesized: Boolean = false,
) {
    sealed class Type {
        data object OneOnOne : Type()
        data object Group : Type()
    }

    




    val displayUnreadNum: Int
        get() = if (unreadMessageNum > 0) unreadMessageNum else if (isManualUnread) 1 else 0

    val isMuted: Boolean
        get() = effectiveMuted ?: (muteStatus == MuteStatus.MUTED.value)

    
    val isRealPinned: Boolean
        get() = pinnedTime != null && pinnedTime > 0

    



    val isPinned: Boolean
        get() = isRealPinned || isSynthesized

}



enum class FocusRowAction { NONE, START, END }

enum class MuteStatus(val value: Int) {
    MUTED(1),
    UNMUTED(0);
}


data class CallInfo(
    var isJoinEnable: Boolean = false,
    var roomId: String? = null,
)