package com.difft.android.chat.scribbles

import com.difft.android.chat.components.emoji.EmojiUtil
import com.difft.android.imageeditor.core.HiddenEditText

internal class RemoveEmojiTextFilter : HiddenEditText.TextFilter {
    override fun filter(text: String): String? = EmojiUtil.stripEmoji(text)
}
