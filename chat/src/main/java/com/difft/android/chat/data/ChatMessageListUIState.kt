package com.difft.android.chat.data

import com.difft.android.chat.ScrollAction
import com.difft.android.chat.message.ChatMessage
















data class ChatMessageListUIState(
    val chatMessages: List<ChatMessage>,
    val scrollAction: ScrollAction? = null,
    val windowSize: Int = 0,
    val hasReachedHistoryStart: Boolean = false,
    val hasReachedLatest: Boolean = false
)