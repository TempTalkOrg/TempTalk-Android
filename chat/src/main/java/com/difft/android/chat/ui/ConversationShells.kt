package com.difft.android.chat.ui

import android.content.Context
import android.content.Intent
import com.difft.android.base.utils.pane.ConversationOpener
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupChatContentActivity.Companion.groupID
import com.difft.android.chat.ui.ChatActivity.Companion.contactorID
import com.difft.android.chat.ui.ChatActivity.Companion.jumpMessageTimeStamp
import com.difft.android.chat.ui.ChatActivity.Companion.source
import com.difft.android.chat.ui.ChatActivity.Companion.sourceType

/**
 * Entry for opening a conversation from anywhere: `ChatActivity.startActivity` and
 * `GroupChatContentActivity.startActivity` go through [open]. The app installs the conversation
 * router as the Application's [ConversationOpener] (home detail stack or full-window shell); without
 * one, the full-window shell Activity opens as before.
 */
object ConversationShells {

    fun open(context: Context, target: ConversationTarget) {
        val opener = context.applicationContext as? ConversationOpener
        if (opener != null) {
            opener.openConversation(context, target)
        } else {
            context.startActivity(intent(context, target))
        }
    }

    /** The full-window shell Activity for [target]. */
    fun intent(context: Context, target: ConversationTarget): Intent = when (target.kind) {
        ConversationTarget.Kind.ONE_ON_ONE -> Intent(context, ChatActivity::class.java).apply {
            contactorID = target.id
            sourceType = target.sourceType
            source = target.source
            jumpMessageTimeStamp = target.jumpTimestamp
        }
        ConversationTarget.Kind.GROUP -> Intent(context, GroupChatContentActivity::class.java).apply {
            groupID = target.id
            jumpMessageTimeStamp = target.jumpTimestamp
        }
    }
}
