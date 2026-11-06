package com.difft.android.chat.ui

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For

/** Full-window shell for [EmojiReactionFragment] (chat shells and popups). */
@AndroidEntryPoint
class EmojiReactionActivity : DetailShellActivity() {

    companion object {
        fun intent(context: Context, forWhat: For, messageId: String): Intent =
            Intent(context, EmojiReactionActivity::class.java).putExtras(EmojiReactionFragment.arguments(forWhat, messageId))
    }

    override fun createPage(): Fragment = EmojiReactionFragment.newInstance(intent.extras)
}
