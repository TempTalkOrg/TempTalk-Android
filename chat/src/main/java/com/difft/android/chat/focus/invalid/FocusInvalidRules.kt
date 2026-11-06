package com.difft.android.chat.focus.invalid

import androidx.annotation.StringRes
import com.difft.android.chat.R
import com.difft.android.chat.focus.EffectiveFocus

/** Which "focus group is gone" dialog applies: D5 (the group was dismissed or expired) or D5b (removed). */
enum class FocusInvalidKind { DISBANDED, REMOVED }

/** A dialog that must be answered: the [kind], the invalid [episode] it belongs to, its [version] (for logs) and the dead group's [gid]. */
data class PendingFocusDialog(val kind: FocusInvalidKind, val episode: String, val version: Int, val gid: String?)

/**
 * Pure rules for the invalid focus state (design §6). The server's `invalidReason` is the only source:
 * 0 dismissed, 1 removed, 2 left by the user, 3 removed by the system, 4 group expired.
 */
object FocusInvalidRules {
    private const val REASON_DISMISSED = 0
    private const val REASON_REMOVED = 1
    private const val REASON_SELF_LEFT = 2
    private const val REASON_SYSTEM_REMOVED = 3
    private const val REASON_GROUP_EXPIRED = 4

    /** The dialog kind for an invalid reason; null for a self-initiated leave (D4 already warned) and unknown values. */
    fun kindOf(reason: Int?): FocusInvalidKind? = when (reason) {
        REASON_DISMISSED, REASON_GROUP_EXPIRED -> FocusInvalidKind.DISBANDED
        REASON_REMOVED, REASON_SYSTEM_REMOVED -> FocusInvalidKind.REMOVED
        REASON_SELF_LEFT -> null
        else -> null
    }

    /**
     * Identity of an invalid episode: the reason plus the dead group ids (the server keeps the invalid gid in
     * `groupIds`). Editing options while invalid bumps the version but not the episode, so an answered dialog
     * or a closed strip stays answered; a different reason or group is a new episode.
     */
    fun episodeKey(focus: EffectiveFocus): String = "${focus.invalidReason}:${focus.groupIds.sorted().joinToString(",")}"

    /**
     * The dialog still owed to the user: the effective focus is invalid for a reason that has one and its
     * episode is not the last answered one ([handledEpisode]). A newer version that is no longer invalid yields null,
     * which is how a dialog disappears when another device already acted.
     */
    fun pendingDialog(focus: EffectiveFocus, handledEpisode: String): PendingFocusDialog? {
        if (!focus.invalid) return null
        val kind = kindOf(focus.invalidReason) ?: return null
        val episode = episodeKey(focus)
        if (episode == handledEpisode) return null
        return PendingFocusDialog(kind, episode, focus.version, focus.focusGid)
    }

    /** The home status strip shows for every invalid state (including a self-leave) not closed for this episode. */
    fun stripVisible(focus: EffectiveFocus, dismissedEpisode: String): Boolean = focus.invalid && episodeKey(focus) != dismissedEpisode

    /** The leave-confirm body for the current valid focus group (D4); null means the unchanged default text. */
    @StringRes
    fun leaveNoticeRes(focus: EffectiveFocus, gid: String): Int? =
        if (focus.isFocusedGroup(gid)) R.string.focus_leave_focused_notice else null
}
