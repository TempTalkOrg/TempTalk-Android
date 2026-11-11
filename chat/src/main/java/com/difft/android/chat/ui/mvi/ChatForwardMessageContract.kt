package com.difft.android.chat.ui.mvi

import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext

object ChatForwardMessageContract {

    sealed interface Intent {
        
        data class SetRoot(
            val context: ForwardContext,
            val outerConversation: For?,
            val outerAuthorId: String?,
        ) : Intent

        data class PushNested(val context: ForwardContext) : Intent

        
        data class TrimTo(val size: Int) : Intent

        
        data class ReplaceAt(val index: Int, val context: ForwardContext) : Intent
    }

    




    data class State(
        val stack: List<ForwardContext> = emptyList(),
        val outerSourceConversation: For? = null,
        val outerSourceAuthorId: String? = null,
    )
}
