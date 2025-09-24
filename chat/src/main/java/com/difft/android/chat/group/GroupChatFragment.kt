package com.difft.android.chat.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.difft.android.base.fragment.DisposableManageFragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RxUtil
import com.difft.android.call.LCallManager
import com.difft.android.chat.databinding.ChatFragmentGroupChatBinding

class GroupChatFragment : DisposableManageFragment() {
    private lateinit var binding: ChatFragmentGroupChatBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = ChatFragmentGroupChatBinding.inflate(inflater, container, false)
        registerCallStatusViewListener()
        return binding.root
    }

    private fun registerCallStatusViewListener() {
        LCallManager.chatHeaderCallVisibility
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ isVisible ->
                binding.fragmentContainerViewCall.visibility = if (isVisible) View.VISIBLE else View.GONE
            }, {
                L.e { "[Call] GroupChatFragment callStatusView listener error = ${it.message}" }
                it.printStackTrace()
            })
    }
}