package com.difft.android.chat.ui

import android.os.Bundle
import com.difft.android.base.log.lumberjack.L
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.chat.R
import com.difft.android.chat.common.header.CommonHeaderFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.contacts.data.isOfficialBotId
import com.difft.android.chat.databinding.ChatFragmentHeaderBinding
import com.difft.android.chat.group.CreateGroupActivity
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatActivity.Companion.source
import com.difft.android.chat.ui.ChatActivity.Companion.sourceType
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.AndroidEntryPoint
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.wcdb

@AndroidEntryPoint
class ChatHeaderFragment : CommonHeaderFragment() {
    // Use parent fragment as ViewModel owner when nested (in ChatFragment),
    // otherwise use activity (when directly in ChatActivity).
    // Parent fragment initializes ViewModels in onCreateView before child fragments are created.
    private val chatViewModel: ChatMessageViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )
    private val chatSettingViewModel: ChatSettingViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    lateinit var binding: ChatFragmentHeaderBinding


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ChatFragmentHeaderBinding.inflate(inflater, container, false)

        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach {
                initView(null)
                // 更新 archiveTime UI
                updateArchiveTimeUI(it.messageExpiry)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        viewLifecycleOwner.lifecycleScope.launch {
            ContactorUtil.friendStatusUpdate.collect {
                if (!isAdded || view == null) return@collect
                if (it.first == chatViewModel.forWhat.id) {
                    initView(it.second)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ContactorUtil.contactsUpdate.collect {
                if (!isAdded || view == null) return@collect
                if (it.contains(chatViewModel.forWhat.id)) {
                    initView(null)
                }
            }
        }

        chatViewModel.chatUIData.onEach {
            initView(null)
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // 给整个 header 区域添加点击事件，跳转到单聊设置页面
        binding.root.setOnClickListener {
            SingleChatSettingActivity.startActivity(requireActivity(), chatViewModel.forWhat.id)
        }

//        binding.imageviewMenuMore.setOnClickListener {
//            SingleChatSettingActivity.startActivity(requireActivity(), chatViewModel.forWhat.id)
//        }

        // 观察选择状态来切换返回按钮行为
        chatViewModel.selectMessagesState.onEach {
            if (isInDualPaneMode() && !it.editModel) {
                // In dual-pane mode, hide back button when not in edit mode
                binding.ibBack.visibility = View.GONE
            } else if (it.editModel) {
                binding.ibBack.visibility = View.VISIBLE
                binding.ibBack.setImageResource(R.drawable.chat_icon_close)
                binding.ibBack.setOnClickListener { chatViewModel.selectModel(false) }
            } else {
                binding.ibBack.visibility = View.VISIBLE
                binding.ibBack.setImageResource(R.mipmap.chat_tabler_arrow_left)
                binding.ibBack.setOnClickListener { activity?.finish() }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)
        return binding.root
    }

    private fun initView(friend: Boolean?) {
        val chatUIData = chatViewModel.chatUIData.value
        val contact = chatUIData.contact ?: return
//        binding.imageviewMenuMore.visibility = View.GONE
        binding.imageviewAddContact.visibility = View.GONE
        binding.imageviewCreateGroup.visibility = View.GONE
        binding.buttonCall.visibility = View.GONE
        binding.imageviewBotBadge.isVisible = contact.id.isOfficialBotId()

        if (contact.id == globalServices.myId) {
            binding.textviewNickname.text = getString(com.difft.android.base.R.string.chat_favorites)
        } else if (contact.id.isBotId()) {
            binding.textviewNickname.text = contact.getDisplayNameForUI()
        } else {
            binding.textviewNickname.text = contact.getDisplayNameForUI()

            viewLifecycleOwner.lifecycleScope.launch {
                val isFriend = friend ?: withContext(Dispatchers.IO) {
                    wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contact.id)) != null
                }
                if (!isAdded || view == null) return@launch
                if (isFriend) {
                    binding.imageviewCreateGroup.visibility = View.VISIBLE
                    binding.imageviewCreateGroup.setOnClickListener {
                        CreateGroupActivity.startActivity(requireActivity(), arrayListOf(contact.id))
                    }

                    binding.buttonCall.visibility = View.VISIBLE
                    binding.buttonCall.setOnClickListener {
                        lifecycleScope.launch {
                            val chatRoomName = withContext(Dispatchers.IO) {
                                chatViewModel.chatUIData.value.contact?.getDisplayNameForUI() ?: ""
                            }
                            chatViewModel.startCall(requireActivity(), chatRoomName)
                        }
                    }
                } else {
                    binding.imageviewAddContact.visibility = View.VISIBLE
                    binding.imageviewAddContact.setOnClickListener { requestAddFriend() }
                }

                val shouldHideCallButton = !isFriend || chatSettingViewModel.currentConversationSet?.blockStatus == 1
                binding.buttonCall.visibility = if (shouldHideCallButton) View.GONE else View.VISIBLE
            }
        }
    }

    private fun requestAddFriend() {
        var sourceType: String? = null
        var source: String? = null
        if (requireActivity() is ChatActivity) {
            (requireActivity() as ChatActivity).intent?.let {
                sourceType = it.sourceType
                source = it.source
            }
        }
        ComposeDialogManager.showWait(requireActivity(), "")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ContactorUtil.fetchAddFriendRequest(requireContext(), SecureSharedPrefsUtil.getToken(), chatViewModel.forWhat.id, sourceType, source)
                }
                if (!isAdded || view == null) return@launch
                ComposeDialogManager.dismissWait()
                if (result.status == 0) {
                    ToastUtil.show(R.string.contact_request_sent)
                    ContactorUtil.sendFriendRequestMessage(viewLifecycleOwner.lifecycleScope, getString(R.string.contact_friend_request), chatViewModel.forWhat)
                } else {
                    result.reason?.let { message -> ToastUtil.show(message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                ComposeDialogManager.dismissWait()
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }

    /**
     * 更新 archiveTime UI 显示
     */
    private fun updateArchiveTimeUI(archiveTime: Long) {
        if (archiveTime > 0L) {
            binding.textviewTimer.visibility = View.VISIBLE
            val text = " [" + archiveTime.toArchiveTimeDisplayText() + "]"
            binding.textviewTimer.text = text
        } else {
            binding.textviewTimer.visibility = View.GONE
        }
    }
}