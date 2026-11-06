package com.difft.android.chat.focus

/**
 * Local notification decision for the WebSocket delivery path, step for step the server's
 * `GroupPushDecider.decide` (FCM deliveries already passed it, so they are not re-judged here).
 * Pure and Android-free so the shared case table can be unit tested.
 *
 * 1. Private chat: muted -> no, otherwise yes; focus never applies.
 * 2. Group message [Category] from the mentions.
 * 3. Focus active (an invalid focus included): a valid focused group notifies everything; every other
 *    group follows the focus "others allow" options and [Category.OTHER] never notifies.
 * 4. No focus, group muted: the group's allow options decide; [Category.OTHER] never notifies.
 * 5. No focus, not muted: the notification level (single group, or the global one it follows).
 *
 * With [EffectiveFocus.featureEnabled] false step 3 is skipped and step 4 is the old "mute blocks
 * everything"; step 5 is the same either way (the mention level lets @me and @all through).
 */
object GroupNotifyPolicy {

    /** Reserved mention uid meaning @all (matches the message model's `MENTIONS_ALL_ID`). */
    const val MENTIONS_ALL_UID = "MENTIONS_ALL"

    enum class Category {
        GROUP_CALL,

        /** The mention list contains the local user, wherever @all sits in it. */
        MENTION_ME,

        /** @all is mentioned and the local user is not. */
        MENTION_ALL,

        OTHER,
    }

    /** Group message notification level: single-group value, or the global one when the group follows it. */
    enum class NotifyLevel { ALL, MENTION, OFF }

    /** The mute sub-options; null means the server value was never received, read as the server default. */
    data class MuteOptions(
        val allowMentionMe: Boolean? = null,
        val allowMentionAll: Boolean? = null,
        val allowGroupCall: Boolean? = null,
    ) {
        val mentionMe: Boolean get() = allowMentionMe ?: true
        val mentionAll: Boolean get() = allowMentionAll ?: false
        val groupCall: Boolean get() = allowGroupCall ?: false
    }

    /** Which rule produced the decision (mirrors the server's `PushDecision`). */
    enum class Reason {
        PRIVATE_PUSH,
        PRIVATE_MUTED,
        FOCUS_GROUP,
        FOCUS_OTHER_ALLOW,
        FOCUS_OTHER_BLOCK,
        MUTE_ALLOW,
        MUTE_BLOCK,
        LEVEL_ALLOW,
        LEVEL_BLOCK,
    }

    data class Decision(val notify: Boolean, val reason: Reason)

    data class Input(
        /** Group id, or null for a private chat. */
        val gid: String?,
        /** Ignored for a private chat. */
        val category: Category,
        val muted: Boolean,
        val muteOptions: MuteOptions = MuteOptions(),
        val level: NotifyLevel = NotifyLevel.ALL,
        val focus: EffectiveFocus = EffectiveFocus.INACTIVE,
    )

    /**
     * Category from a message's mention uids: a list that contains the local user is @me (even when @all
     * comes first), otherwise one that contains @all is @all, otherwise a plain message for this user.
     * A quote or reply of the user's message is not @me yet (pending product).
     */
    fun categorize(mentionUids: List<String>, myId: String): Category = when {
        myId in mentionUids -> Category.MENTION_ME
        MENTIONS_ALL_UID in mentionUids -> Category.MENTION_ALL
        else -> Category.OTHER
    }

    fun decide(input: Input): Decision {
        val gid = input.gid
        if (gid == null) {
            return if (input.muted) Decision(false, Reason.PRIVATE_MUTED) else Decision(true, Reason.PRIVATE_PUSH)
        }
        val focus = input.focus
        if (focus.featureEnabled && focus.active) {
            return decideFocused(gid, input.category, focus)
        }
        if (input.muted) {
            val allowed = focus.featureEnabled && allowedByMute(input.category, input.muteOptions)
            return Decision(allowed, if (allowed) Reason.MUTE_ALLOW else Reason.MUTE_BLOCK)
        }
        val allowed = allowedByLevel(input.category, input.level)
        return Decision(allowed, if (allowed) Reason.LEVEL_ALLOW else Reason.LEVEL_BLOCK)
    }

    private fun decideFocused(gid: String, category: Category, focus: EffectiveFocus): Decision {
        if (focus.isFocusedGroup(gid)) return Decision(true, Reason.FOCUS_GROUP)
        val allowed = when (category) {
            Category.MENTION_ME -> focus.othersAllowMentionMe
            Category.MENTION_ALL -> focus.othersAllowMentionAll
            Category.GROUP_CALL -> focus.othersAllowGroupCall
            Category.OTHER -> false
        }
        return Decision(allowed, if (allowed) Reason.FOCUS_OTHER_ALLOW else Reason.FOCUS_OTHER_BLOCK)
    }

    private fun allowedByMute(category: Category, options: MuteOptions): Boolean = when (category) {
        Category.MENTION_ME -> options.mentionMe
        Category.MENTION_ALL -> options.mentionAll
        Category.GROUP_CALL -> options.groupCall
        Category.OTHER -> false
    }

    /**
     * Group calls ignore the level. At MENTION level @me and @all both pass, with the feature on or off.
     */
    private fun allowedByLevel(category: Category, level: NotifyLevel): Boolean {
        if (category == Category.GROUP_CALL) return true
        return when (level) {
            NotifyLevel.ALL -> true
            NotifyLevel.OFF -> false
            NotifyLevel.MENTION -> when (category) {
                Category.MENTION_ME, Category.MENTION_ALL -> true
                else -> false
            }
        }
    }
}
