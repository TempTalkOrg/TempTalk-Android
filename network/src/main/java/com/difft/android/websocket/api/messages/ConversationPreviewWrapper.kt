package com.difft.android.websocket.api.messages

import org.whispersystems.signalservice.internal.push.SignalServiceProtos

class ConversationPreviewWrapper(
    val conversationPreview: SignalServiceProtos.ConversationPreview?
)