package com.difft.android.chat.message

/**
 * Synthetic, non-persisted row prepended to the chat message list to explain E2EE, shown only
 * when [com.difft.android.chat.ui.ChatMessageViewModel.isE2eeHintEligible] is true AND the
 * conversation's oldest loaded message is the actual first message
 * ([com.difft.android.chat.ChatMessageListBehavior.hasReachedHistoryStart]).
 */
class EncryptionHeaderChatMessage(
    val isNonFriendVariant: Boolean,
) : ChatMessage() {
    companion object {
        const val STABLE_ID = "__e2ee_header__"
    }

    init {
        id = STABLE_ID
        // ChatMessage.authorId is `lateinit var` with no default (ChatMessage.kt:10);
        // super.equals()/super.hashCode() below dereference it unconditionally — leaving it
        // unset throws UninitializedPropertyAccessException the first time this row is bound or
        // diffed. Same explicit-assignment convention as Record2MessageFactory.kt:164/:177.
        authorId = ""
        systemShowTimestamp = Long.MIN_VALUE // never compared post-injection; defensive only
        showName = false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptionHeaderChatMessage) return false
        if (!super.equals(other)) return false
        return isNonFriendVariant == other.isNonFriendVariant
    }

    override fun hashCode(): Int = 31 * super.hashCode() + isNonFriendVariant.hashCode()
}
