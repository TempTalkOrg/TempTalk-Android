package com.difft.android.websocket.api.messages

import difft.android.messageserialization.For


enum class ConversationVerdict { PROCESS, REJECT }












fun crossCheckConversation(
    contentConversation: For,
    envelopeConversation: For?,
    isSyncOrSelf: Boolean,
): ConversationVerdict {
    if (isSyncOrSelf) return ConversationVerdict.PROCESS
    if (envelopeConversation == null) return ConversationVerdict.PROCESS 
    return if (contentConversation == envelopeConversation) {
        ConversationVerdict.PROCESS
    } else {
        ConversationVerdict.REJECT 
    }
}
