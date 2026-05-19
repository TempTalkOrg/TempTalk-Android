package com.difft.android.chat.ui

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Custom view for chat message items.
 * Used as the root view of message list items to enable click/long-click handling.
 */
class ChatMessageItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs)
