package com.difft.android.chat.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.BUNDLE_KEY_SOURCE
import com.difft.android.chat.contacts.contactsdetail.BUNDLE_KEY_SOURCE_TYPE
import com.difft.android.chat.databinding.ChatActivityChatBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ChatActivity : BaseActivity(), ChatMessageListProvider, ChatInputFocusable {

    // Disable BaseActivity bottom padding - ChatFragment handles IME and navigation bar
    override fun shouldApplyNavigationBarPadding(): Boolean = false

    companion object {
        const val BUNDLE_KEY_CONTACT_ID = "BUNDLE_KEY_CONTACT_ID"
        const val JUMP_TO_MESSAGE_ID = "JUMP_TO_MESSAGE_ID"

        fun startActivity(
            activity: Context,
            contactID: String,
            sourceType: String? = null,
            source: String? = null,
            jumpMessageTimeStamp: Long? = null
        ) {
            val intent = Intent(activity, ChatActivity::class.java)
            intent.contactorID = contactID
            intent.sourceType = sourceType
            intent.source = source
            intent.jumpMessageTimeStamp = jumpMessageTimeStamp
            activity.startActivity(intent)
        }

        var Intent.contactorID: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_ID)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_ID, value)
            }

        var Intent.sourceType: String?
            get() = getStringExtra(BUNDLE_KEY_SOURCE_TYPE)
            set(value) {
                putExtra(BUNDLE_KEY_SOURCE_TYPE, value)
            }
        var Intent.source: String?
            get() = getStringExtra(BUNDLE_KEY_SOURCE)
            set(value) {
                putExtra(BUNDLE_KEY_SOURCE, value)
            }

        var Intent.jumpMessageTimeStamp: Long?
            get() = getLongExtra(JUMP_TO_MESSAGE_ID, 0L)
            set(value) {
                putExtra(JUMP_TO_MESSAGE_ID, value)
            }
    }

    private val mBinding: ChatActivityChatBinding by viewbind()

    private var chatFragment: ChatFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ChatBackgroundDrawable(this, mBinding.root, true))

        if (savedInstanceState == null) {
            chatFragment = ChatFragment.newInstance(
                contactId = intent.contactorID ?: "",
                sourceType = intent.sourceType,
                source = intent.source,
                jumpMessageTimestamp = intent.jumpMessageTimeStamp
            )
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container_chat, chatFragment!!)
                .commit()
        } else {
            chatFragment = supportFragmentManager.findFragmentById(R.id.fragment_container_chat) as? ChatFragment
        }
    }

    override fun getChatMessageListFragment(): ChatMessageListFragment? {
        return chatFragment?.getChatMessageListFragment()
    }

    override fun focusCurrentChatInputIfMatches(conversationId: String): Boolean {
        if (intent.contactorID == conversationId) {
            chatFragment?.focusInputAndShowKeyboard()
            return true
        }
        return false
    }
}
