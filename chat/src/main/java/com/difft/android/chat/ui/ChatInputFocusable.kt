package com.difft.android.chat.ui

/**
 * Interface for activities that can focus the chat input and show keyboard.
 * Implemented by ChatActivity and IndexActivity to support focusing chat input
 * after dismissing contact detail popup.
 */
interface ChatInputFocusable {
    /**
     * Focus the chat input and show keyboard if the current conversation matches.
     * @param conversationId The conversation ID to match
     * @return true if the current conversation matches and keyboard was shown, false otherwise
     */
    fun focusCurrentChatInputIfMatches(conversationId: String): Boolean
}
