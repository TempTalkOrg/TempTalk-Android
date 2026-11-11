package com.difft.android.chat.ui

import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.isConfidential










internal fun pendingConfidentialBurnIds(
    audio: ChatMessage?,
    text: ChatMessage?,
    attachment: ChatMessage?,
    viewerConsumed: Boolean,
): ArrayList<String> {
    val ids = ArrayList<String>()
    if (viewerConsumed) {
        audio?.takeIf { it.owesBurn() }?.let { ids.add(it.id) }
        text?.takeIf { it.owesBurn() }?.let { ids.add(it.id) }
    }
    attachment?.takeIf { it.owesBurn() }?.let { ids.add(it.id) }
    return ids
}

private fun ChatMessage.owesBurn(): Boolean = !isMine && isConfidential()
