package com.difft.android.chat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.chat.R
import com.difft.android.chat.common.header.CommonHeaderFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatFragmentHeaderBinding
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.group.CreateGroupActivity
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.archive.MessageArchiveUtil
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatActivity.Companion.source
import com.difft.android.chat.ui.ChatActivity.Companion.sourceType
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBContactorModel
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class ChatHeaderFragment : CommonHeaderFragment() {
    private val chatViewModel: ChatMessageViewModel by activityViewModels()
    private val chatSettingViewModel: ChatSettingViewModel by activityViewModels()
    private var chatUIData: ChatUIData? = null
    private var conversationSet: ConversationSetResponseBody? = null

    lateinit var binding: ChatFragmentHeaderBinding

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var dbRoomStore: DBRoomStore

    @Inject
    lateinit var wcdb: WCDB

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ChatFragmentHeaderBinding.inflate(inflater, container, false)

        chatSettingViewModel.conversationSet
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                conversationSet = it
                initView(null)
            }, { it.printStackTrace() })

        ContactorUtil.friendStatusUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.first == chatViewModel.forWhat.id) {
                    initView(it.second)
                }
            }, { it.printStackTrace() })

        ContactorUtil.contactsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.contains(chatViewModel.forWhat.id)) {
                    initView(null)
                }
            }, { it.printStackTrace() })

        chatViewModel.chatUIData.onEach {
            chatUIData = it
            initView(null)
        }.launchIn(viewLifecycleOwner.lifecycleScope)


        binding.textviewNickname.setOnClickListener {
//            ContactDetailActivity.startActivity(requireContext(), chatViewModel.forWhat.id)
            SingleChatSettingActivity.startActivity(requireActivity(), chatViewModel.forWhat.id)
        }

//        binding.imageviewMenuMore.setOnClickListener {
//            SingleChatSettingActivity.startActivity(requireActivity(), chatViewModel.forWhat.id)
//        }

        chatViewModel.showReactionShade
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it) binding.reactionsShade.visibility = View.VISIBLE else binding.reactionsShade.visibility = View.GONE
            }, { it.printStackTrace() })

        MessageArchiveUtil.archiveTimeUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                binding.textviewTimer.visibility = View.GONE
                if (it.first == chatViewModel.forWhat.id && it.second > 0L) {
                    binding.textviewTimer.visibility = View.VISIBLE
                    val text = " [" + it.second.toArchiveTimeDisplayText() + "]"
                    binding.textviewTimer.text = text
                }
            }, { it.printStackTrace() })

        chatViewModel.chatMessageListUIState.onEach {
            if (it.chatMessages.any { it.editMode }) {
                binding.ibBack.setImageResource(R.drawable.chat_icon_close)
                binding.ibBack.setOnClickListener { chatViewModel.selectModel(false) }
            } else {
                binding.ibBack.setImageResource(R.mipmap.chat_tabler_arrow_left)
                binding.ibBack.setOnClickListener { activity?.finish() }
            }

        }.launchIn(viewLifecycleOwner.lifecycleScope)
        return binding.root
    }

    private fun initView(friend: Boolean?) {
        val chatUIData = chatUIData ?: return
        val contact = chatUIData.contact ?: return
//        binding.imageviewMenuMore.visibility = View.GONE
        binding.imageviewAddContact.visibility = View.GONE
        binding.imageviewCreateGroup.visibility = View.GONE
        binding.buttonCall.visibility = View.GONE

        if (contact.id == globalServices.myId) {
            binding.textviewNickname.text = getString(R.string.chat_favorites)
        } else if (contact.id.isBotId()) {
            binding.textviewNickname.text = contact.getDisplayNameForUI()
        } else {
            binding.textviewNickname.text = contact.getDisplayNameForUI()

            viewLifecycleOwner.lifecycleScope.launch {
                val isFriend = friend ?: withContext(Dispatchers.IO) {
                    wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contact.id)) != null
                }
                if (isFriend) {
                    binding.imageviewCreateGroup.visibility = View.VISIBLE
                    binding.imageviewCreateGroup.setOnClickListener {
                        CreateGroupActivity.startActivity(requireActivity(), arrayListOf(contact.id))
                    }

                    binding.buttonCall.visibility = View.VISIBLE
                    binding.buttonCall.setOnClickListener {
                        lifecycleScope.launch {
                            val chatRoomName = withContext(Dispatchers.IO) {
                                chatUIData.let {
                                    it.contact?.getDisplayNameForUI()
                                } ?: LCallManager.getDisplayName(chatViewModel.forWhat.id) ?: ""
                            }
                            chatViewModel.startCall(requireActivity(), chatRoomName)
                        }
                    }
                } else {
                    binding.imageviewAddContact.visibility = View.VISIBLE
                    binding.imageviewAddContact.setOnClickListener { requestAddFriend() }
                }

                if (conversationSet?.blockStatus == 1) {
                    binding.buttonCall.visibility = View.GONE
                } else {
                    binding.buttonCall.visibility = View.VISIBLE
                }
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
        ContactorUtil.fetchAddFriendRequest(requireContext(), SecureSharedPrefsUtil.getToken(), chatViewModel.forWhat.id, sourceType, source)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                ComposeDialogManager.dismissWait()
                if (it.status == 0) {
                    ToastUtil.show(R.string.contact_request_sent)
                    ContactorUtil.sendFriendRequestMessage(requireActivity(), chatViewModel.forWhat)
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }) {
                ComposeDialogManager.dismissWait()
                it.message?.let { message -> ToastUtil.show(message) }
            }
    }
}