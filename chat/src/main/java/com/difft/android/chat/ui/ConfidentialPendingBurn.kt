package com.difft.android.chat.ui

import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.isConfidential

/**
 * Ids of confidential messages whose open viewer would burn them on close, saved when the viewer
 * is torn down by recreation (the close handler never runs then, #1440). Mirrors
 * [ChatMessageListFragment.onConfidentialAudioDialogDismiss], `onConfidentialTextDialogDismiss`
 * and `onConfidentialAttachmentDialogDismiss`: voice and text burn only once revealed, an
 * attachment burns once opened; own messages never burn.
 *
 * Pure so the decision table is unit-testable without the fragment's Hilt graph.
 */
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
