package com.difft.android.chat.focus.settings

import com.difft.android.base.user.GlobalNotificationType

/** An item that can still notify while a group is muted (or while another group is focused). */
enum class StillNotifyItem { MENTION_ME, MENTION_ALL, GROUP_CALL }

/** When the running focus ends; null in [GroupFocusSettingsContract.State] when there is none to show. */
sealed interface FocusEnd {
    data object Manual : FocusEnd

    /** [time] is the localized clock time; [tomorrow] when it is not on today's date. */
    data class At(val time: String, val tomorrow: Boolean) : FocusEnd
}

/** The caption under the focus card (PRD 7.1.3). */
sealed interface FocusCaption {
    /** Not focusing. */
    data object Off : FocusCaption

    /** This group is the focus group. */
    data object OthersMuted : FocusCaption

    /** Another group is the focus group. */
    data class OtherGroup(val name: String, val end: FocusEnd) : FocusCaption

    /** The focus group is gone (error color). */
    data object Invalid : FocusCaption
}

/** The caption under the notification card explaining what the paused rows return to (PRD 7.1.4). */
sealed interface TakeoverCaption {
    data object None : TakeoverCaption

    /** Focus is on: Mute returns to [muted], Notifications to [level]. */
    data class ByFocus(val muted: Boolean, val level: GlobalNotificationType) : TakeoverCaption

    /** The group is muted: Notifications returns to [level] when unmuted. */
    data class ByMute(val level: GlobalNotificationType) : TakeoverCaption
}

enum class PausedDialog {
    /** D6: paused Mute row while focus is on. */
    MUTE_BY_FOCUS,

    /** D7: paused Notifications row while focus is on. */
    NOTIFY_BY_FOCUS,

    /** D8: paused Notifications row while the group is muted. */
    NOTIFY_BY_MUTE,
}

/** Contract of the group settings notification section (focus card + mute / notification / sound card). */
object GroupFocusSettingsContract {

    sealed interface Intent {
        /** Fed by the host whenever the conversation config changes (the write path owns it). */
        data class ConversationChanged(
            val muted: Boolean,
            val allowMentionMe: Boolean?,
            val allowMentionAll: Boolean?,
            val allowGroupCall: Boolean?,
        ) : Intent

        /** Re-reads the group name, notification level and sound (on resume). */
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
        /** False hides every focus-related entry and keeps the old Mute / Notifications rows. */
        val featureEnabled: Boolean = false,
        val focusOn: Boolean = false,
        val focusCaption: FocusCaption = FocusCaption.Off,
        /** Set only while [focusOn]. */
        val focusEnd: FocusEnd? = null,
        val othersStillNotify: List<StillNotifyItem> = emptyList(),
        val foldOthers: Boolean = true,
        val muted: Boolean = false,
        /** Focus is active: the Mute row shows "Paused" instead of a switch. */
        val mutePaused: Boolean = false,
        /** Shown only while muted and not focusing. */
        val showMuteExceptions: Boolean = false,
        val muteExceptions: List<StillNotifyItem> = emptyList(),
        val level: GlobalNotificationType = GlobalNotificationType.ALL,
        /** Focus is active or the group is muted: the Notifications row shows "Paused". */
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

        /** Write the mute switch through the conversation config path. */
        data class WriteMute(val muted: Boolean) : Effect
        data class ShowPausedDialog(val kind: PausedDialog) : Effect
        data object ShowError : Effect
    }
}
