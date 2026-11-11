package com.difft.android.chat.focus.invalid

import androidx.annotation.StringRes
import com.difft.android.chat.R
import com.difft.android.chat.focus.EffectiveFocus


enum class FocusInvalidKind { DISBANDED, REMOVED }


data class PendingFocusDialog(val kind: FocusInvalidKind, val episode: String, val version: Int, val gid: String?)





object FocusInvalidRules {
    private const val REASON_DISMISSED = 0
    private const val REASON_REMOVED = 1
    private const val REASON_SELF_LEFT = 2
    private const val REASON_SYSTEM_REMOVED = 3
    private const val REASON_GROUP_EXPIRED = 4

    
    fun kindOf(reason: Int?): FocusInvalidKind? = when (reason) {
        REASON_DISMISSED, REASON_GROUP_EXPIRED -> FocusInvalidKind.DISBANDED
        REASON_REMOVED, REASON_SYSTEM_REMOVED -> FocusInvalidKind.REMOVED
        REASON_SELF_LEFT -> null
        else -> null
    }

    




    fun episodeKey(focus: EffectiveFocus): String = "${focus.invalidReason}:${focus.groupIds.sorted().joinToString(",")}"

    




    fun pendingDialog(focus: EffectiveFocus, handledEpisode: String): PendingFocusDialog? {
        if (!focus.invalid) return null
        val kind = kindOf(focus.invalidReason) ?: return null
        val episode = episodeKey(focus)
        if (episode == handledEpisode) return null
        return PendingFocusDialog(kind, episode, focus.version, focus.focusGid)
    }

    
    fun stripVisible(focus: EffectiveFocus, dismissedEpisode: String): Boolean = focus.invalid && episodeKey(focus) != dismissedEpisode

    
    @StringRes
    fun leaveNoticeRes(focus: EffectiveFocus, gid: String): Int? =
        if (focus.isFocusedGroup(gid)) R.string.focus_leave_focused_notice else null
}
