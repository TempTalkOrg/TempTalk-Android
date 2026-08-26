package com.difft.android.chat.data

import com.difft.android.chat.ScrollAction
import com.difft.android.chat.message.ChatMessage

/**
 * 聊天消息列表 UI 状态
 * @param chatMessages 消息列表
 * @param scrollAction 滚动动作，null 表示不强制滚动，由 Fragment 根据 isAtBottom 自行判断
 * @param windowSize size of the LOADED window (`ChatMessageListBehavior.messageList.size`), NOT
 *   [chatMessages]`.size`: the latter is the post-transform list (empty notify rows filtered out,
 *   E2EE header prepended), so using it would make the trim high-water mark fire early in
 *   conversations with a header and late in conversations with empty notifies.
 * @param hasReachedHistoryStart pass-through of `ChatMessageListBehavior.hasReachedHistoryStart`.
 *   Never cached: the controller recomputes it on every emission that can change it (the sole
 *   carry-forward is `addOneMessage`, where appending at the newest end provably cannot), and this
 *   field is replaced whole with each state, so a row inserted older than the window still flips it
 *   back.
 * @param hasReachedLatest pass-through of `ChatMessageListBehavior.hasReachedLatest`.
 */
data class ChatMessageListUIState(
    val chatMessages: List<ChatMessage>,
    val scrollAction: ScrollAction? = null,
    val windowSize: Int = 0,
    val hasReachedHistoryStart: Boolean = false,
    val hasReachedLatest: Boolean = false
)