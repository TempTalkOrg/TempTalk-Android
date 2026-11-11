package com.difft.android.chat.focus.settings

import com.difft.android.base.user.GlobalNotificationType


enum class StillNotifyItem { MENTION_ME, MENTION_ALL, GROUP_CALL }


sealed interface FocusEnd {
    data object Manual : FocusEnd

    
    data class At(val time: String, val tomorrow: Boolean) : FocusEnd
}


sealed interface FocusCaption {
    
    data object Off : FocusCaption

    
    data object OthersMuted : FocusCaption

    
    data class OtherGroup(val name: String, val end: FocusEnd) : FocusCaption

    
    data object Invalid : FocusCaption
}


sealed interface TakeoverCaption {
    data object None : TakeoverCaption

    
    data class ByFocus(val muted: Boolean, val level: GlobalNotificationType) : TakeoverCaption

    
    data class ByMute(val level: GlobalNotificationType) : TakeoverCaption
}

enum class PausedDialog {
    
    MUTE_BY_FOCUS,

    
    NOTIFY_BY_FOCUS,

    
    NOTIFY_BY_MUTE,
}


object GroupFocusSettingsContract {

    sealed interface Intent {
        
        data class ConversationChanged(
            val muted: Boolean,
            val allowMentionMe: Boolean?,
            val allowMentionAll: Boolean?,
            val allowGroupCall: Boolean?,
        ) : Intent

        
        data object Refresh : Intent
        data class ToggleFocus(val requested: Boolean) : Intent
        data object TapEndTime : Intent
        data object TapOthersStillNotify : Intent
        data class SetFoldOthers(val requested: Boolean) : Intent
        data class ToggleMute(val requested: Boolean) : Intent
        data object TapMuteExceptions : Intent
        data object TapMutePaused : Intent
        data object TapNotification : Intent
        data object TapSound : Intent
    }

    data class State(
        
        val featureEnabled: Boolean = false,
        val focusOn: Boolean = false,
        val focusCaption: FocusCaption = FocusCaption.Off,
        
        val focusEnd: FocusEnd? = null,
        val othersStillNotify: List<StillNotifyItem> = emptyList(),
        val foldOthers: Boolean = true,
        val muted: Boolean = false,
        
        val mutePaused: Boolean = false,
        
        val showMuteExceptions: Boolean = false,
        val muteExceptions: List<StillNotifyItem> = emptyList(),
        val level: GlobalNotificationType = GlobalNotificationType.ALL,
        
        val notifyPaused: Boolean = false,
        val takeoverCaption: TakeoverCaption = TakeoverCaption.None,
        val soundVisible: Boolean = false,
        val soundLabel: String? = null,
    )

    sealed interface Effect {
        data class RequestFocus(val gid: String, val groupName: String) : Effect
        data object EndFocus : Effect
        data object ChangeEndTime : Effect
        data object OpenOthersStillNotify : Effect
        data object OpenMuteExceptions : Effect
        data object OpenNotificationLevel : Effect
        data class OpenSound(val gid: String, val groupName: String) : Effect

        
        data class WriteMute(val muted: Boolean) : Effect
        data class ShowPausedDialog(val kind: PausedDialog) : Effect
        data object ShowError : Effect
    }
}
