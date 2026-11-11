package com.difft.android.chat.setting

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import difft.android.messageserialization.For





internal object ConversationTargetArgs {
    const val KEY_TARGET_ID = "EXTRA_KEY_TARGET_ID"
    const val KEY_IS_GROUP = "EXTRA_KEY_IS_GROUP"

    fun bundle(target: For): Bundle = Bundle().apply {
        putString(KEY_TARGET_ID, target.id)
        putBoolean(KEY_IS_GROUP, target is For.Group)
    }

    fun read(args: Bundle?): For? {
        val id = args?.getString(KEY_TARGET_ID)
        if (id.isNullOrEmpty()) return null
        return if (args.getBoolean(KEY_IS_GROUP, false)) For.Group(id) else For.Account(id)
    }

    fun read(intent: Intent): For? = read(intent.extras)
}


internal fun Fragment.conversationTargetArg(): For? = ConversationTargetArgs.read(arguments)
