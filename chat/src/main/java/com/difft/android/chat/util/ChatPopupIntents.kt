package com.difft.android.chat.util

import android.content.Context
import android.content.Intent
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.chat.group.GroupChatPopupActivity
import com.difft.android.chat.ui.ChatPopupActivity





object ChatPopupIntents {

    fun create(context: Context, contactId: String?, groupId: String?): Intent? = when {
        !groupId.isNullOrEmpty() && ValidatorUtil.isGid(groupId) ->
            Intent(context, GroupChatPopupActivity::class.java)
                .putExtra(GroupChatPopupActivity.INTENT_EXTRA_GROUP_ID, groupId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        !contactId.isNullOrEmpty() && ValidatorUtil.isUid(contactId) ->
            Intent(context, ChatPopupActivity::class.java)
                .putExtra(ChatPopupActivity.BUNDLE_KEY_CONTACT_ID, contactId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        else -> null
    }
}
