package com.difft.android.chat.ui.mvi

import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext

object ChatForwardMessageContract {

    sealed interface Intent {
        /** Replaces the stack with the root forward of the opened message. */
        data class SetRoot(
            val context: ForwardContext,
            val outerConversation: For?,
            val outerAuthorId: String?,
        ) : Intent

        data class PushNested(val context: ForwardContext) : Intent

        /** Drops nested levels above [size] after the fragment back stack popped. */
        data class TrimTo(val size: Int) : Intent
    }

    /**
     * [stack] is index-aligned with the viewer's fragments (root at 0, one entry per back-stack
     * level) and survives configuration changes together with the fragment back stack (#1440).
     * The outer source attributes copy/forward notices to the sender of the outer message.
     */
    data class State(
        val stack: List<ForwardContext> = emptyList(),
        val outerSourceConversation: For? = null,
        val outerSourceAuthorId: String? = null,
    )
}
