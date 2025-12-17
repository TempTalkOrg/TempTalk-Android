package com.difft.android.chat.data

import com.difft.android.chat.ScrollAction
import com.difft.android.chat.message.ChatMessage

/**
 * 聊天消息列表 UI 状态
 * @param chatMessages 消息列表
 * @param scrollAction 滚动动作，null 表示不强制滚动，由 Fragment 根据 isAtBottom 自行判断
 */
data class ChatMessageListUIState(
    val chatMessages: List<ChatMessage>,
    val scrollAction: ScrollAction? = null
)