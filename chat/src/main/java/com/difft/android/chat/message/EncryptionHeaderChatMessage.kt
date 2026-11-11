package com.difft.android.chat.message







class EncryptionHeaderChatMessage(
    val isNonFriendVariant: Boolean,
) : ChatMessage() {
    companion object {
        const val STABLE_ID = "__e2ee_header__"
    }

    init {
        id = STABLE_ID
        
        
        
        
        authorId = ""
        systemShowTimestamp = Long.MIN_VALUE 
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
