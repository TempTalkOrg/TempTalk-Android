package com.difft.android.chat.setting

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For

/** Full-window shell for [ChatArchiveSettingsFragment] where it is not pushed onto a home stack. */
@AndroidEntryPoint
class ChatArchiveSettingsActivity : DetailShellActivity() {

    companion object {
        fun start(activity: Activity, target: For) {
            activity.startActivity(intent(activity, target))
        }

        fun intent(context: Context, target: For): Intent =
            Intent(context, ChatArchiveSettingsActivity::class.java).putExtras(ConversationTargetArgs.bundle(target))
    }

    override fun createPage(): Fragment? = ConversationTargetArgs.read(intent)?.let { ChatArchiveSettingsFragment.newInstance(it) }
}
