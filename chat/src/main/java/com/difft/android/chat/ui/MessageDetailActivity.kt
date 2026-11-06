package com.difft.android.chat.ui

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint

/** Full-window shell for [MessageDetailFragment] (chat shells and popups). */
@AndroidEntryPoint
class MessageDetailActivity : DetailShellActivity() {

    companion object {
        fun intent(context: Context, messageId: String, bitmapKey: String?): Intent =
            Intent(context, MessageDetailActivity::class.java)
                .putExtras(MessageDetailFragment.arguments(messageId, bitmapKey))
    }

    override fun createPage(): Fragment? {
        val messageId = intent.getStringExtra(MessageDetailFragment.ARG_MESSAGE_ID)?.takeIf { it.isNotEmpty() } ?: return null
        return MessageDetailFragment.newInstance(messageId, intent.getStringExtra(MessageDetailFragment.ARG_BITMAP_KEY))
    }
}
