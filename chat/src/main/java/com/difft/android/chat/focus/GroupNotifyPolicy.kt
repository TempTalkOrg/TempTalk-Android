package com.difft.android.chat.focus
















object GroupNotifyPolicy {

    
    const val MENTIONS_ALL_UID = "MENTIONS_ALL"

    enum class Category {
        GROUP_CALL,

        
        MENTION_ME,

        
        MENTION_ALL,

        OTHER,
    }

    
    enum class NotifyLevel { ALL, MENTION, OFF }

    
    data class MuteOptions(
        val allowMentionMe: Boolean? = null,
        val allowMentionAll: Boolean? = null,
        val allowGroupCall: Boolean? = null,
    ) {
        val mentionMe: Boolean get() = allowMentionMe ?: true
        val mentionAll: Boolean get() = allowMentionAll ?: false
        val groupCall: Boolean get() = allowGroupCall ?: false
    }

    
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
        
        val gid: String?,
        
        val category: Category,
        val muted: Boolean,
        val muteOptions: MuteOptions = MuteOptions(),
        val level: NotifyLevel = NotifyLevel.ALL,
        val focus: EffectiveFocus = EffectiveFocus.INACTIVE,
    )

    




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
