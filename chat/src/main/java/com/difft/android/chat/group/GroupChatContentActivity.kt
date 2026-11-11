package com.difft.android.chat.group

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.chat.ui.ConversationShells
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatActivityGroupChatContentBinding
import com.difft.android.chat.ui.ChatActivity.Companion.jumpMessageTimeStamp
import com.difft.android.chat.ui.ChatBackgroundDrawable
import com.difft.android.chat.ui.ChatMessageListFragment
import com.difft.android.chat.ui.ChatMessageListProvider
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GroupChatContentActivity : BaseActivity(), ChatMessageListProvider {

    
    override fun shouldApplyNavigationBarPadding(): Boolean = false

    
    override fun shouldApplyStatusBarPadding(): Boolean = false

    override fun participatesInSideChrome(): Boolean = true
    companion object {
        const val INTENT_EXTRA_GROUP_ID = "INTENT_EXTRA_GROUP_ID"

        var Intent.groupID: String?
            get() = getStringExtra(INTENT_EXTRA_GROUP_ID)
            set(value) {
                putExtra(INTENT_EXTRA_GROUP_ID, value)
            }

        fun startActivity(
            activity: Context,
            groupID: String,
            jumpMessageTimeStamp: Long? = null
        ) {
            
            ConversationShells.open(
                activity,
                ConversationTarget(ConversationTarget.Kind.GROUP, groupID, jumpMessageTimeStamp),
            )
        }
    }

    private val mBinding: ChatActivityGroupChatContentBinding by viewbind()

    private var groupChatFragment: GroupChatFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        mBinding.root
        window.setBackgroundDrawable(ChatBackgroundDrawable(this))

        if (savedInstanceState == null) {
            groupChatFragment = GroupChatFragment.newInstance(
                groupId = intent.groupID ?: "",
                jumpMessageTimestamp = intent.jumpMessageTimeStamp
            )
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container_group_chat, groupChatFragment!!)
                .commit()
        } else {
            groupChatFragment = supportFragmentManager.findFragmentById(R.id.fragment_container_group_chat) as? GroupChatFragment
        }
    }

    fun openGroupInfoActivity() {
        groupChatFragment?.openGroupInfoActivity()
    }

    override fun getChatMessageListFragment(): ChatMessageListFragment? {
        return groupChatFragment?.getChatMessageListFragment()
    }
}