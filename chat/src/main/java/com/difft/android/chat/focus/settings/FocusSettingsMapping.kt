package com.difft.android.chat.focus.settings

import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusTimeFormat
import com.difft.android.chat.focus.GroupNotifyPolicy.MuteOptions


internal fun EffectiveFocus.endLabel(now: Long): FocusEnd? {
    if (!active) return null
    val end = until ?: return FocusEnd.Manual
    val label = FocusTimeFormat.endLabel(until = end, now = now)
    return FocusEnd.At(label.time, label.tomorrow)
}


internal fun EffectiveFocus.othersStillNotify(): List<StillNotifyItem> = stillNotifyItems(
    mentionMe = othersAllowMentionMe,
    mentionAll = othersAllowMentionAll,
    groupCall = othersAllowGroupCall,
)


internal fun MuteOptions.stillNotifyItems(): List<StillNotifyItem> = stillNotifyItems(mentionMe, mentionAll, groupCall)

private fun stillNotifyItems(mentionMe: Boolean, mentionAll: Boolean, groupCall: Boolean): List<StillNotifyItem> = buildList {
    if (mentionMe) add(StillNotifyItem.MENTION_ME)
    if (mentionAll) add(StillNotifyItem.MENTION_ALL)
    if (groupCall) add(StillNotifyItem.GROUP_CALL)
}
