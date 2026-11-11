package com.difft.android.chat.ui

import com.difft.android.base.utils.pane.PageWait
import android.os.Bundle
import com.difft.android.base.log.lumberjack.L
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.utils.DualPaneUtils.setupDynamicBackButton
import com.difft.android.base.ui.sidechrome.sideChromeBar
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.chat.R
import com.difft.android.chat.common.header.CommonHeaderFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.databinding.ChatFragmentHeaderBinding
import com.difft.android.chat.group.create.openCreateGroupFromConversation
import com.difft.android.chat.setting.archive.bindCompactExpiry
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.AndroidEntryPoint
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.wcdb

@AndroidEntryPoint
class ChatHeaderFragment : CommonHeaderFragment() {
    
    private val pageWait = PageWait(this)

    
    
    
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
        ChatHeaderChrome.apply(
            this, binding.ibBack, binding.titlePill, binding.imageviewTitleAvatar, binding.titleText,
            binding.nameRow, binding.timerRow, binding.actionsCapsule,
        )

        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach {
                initView(null)
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

        
        
        OfficialAccountCache.state
            .map { chatViewModel.forWhat.id in it }
            .distinctUntilChanged()
            .drop(1)
            .onEach { initView(null) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.root.setOnClickListener {
            detailNavigation().push(this, SingleChatSettingDestination(chatViewModel.forWhat.id))
        }


        chatViewModel.selectMessagesState.onEach {
            setupDynamicBackButton(
                backButton = binding.ibBack,
                isEditMode = it.editModel,
                backIconRes = com.difft.android.base.R.drawable.chative_ic_back,
                closeIconRes = R.drawable.chat_ic_close_24,
                onExitEditMode = { chatViewModel.selectModel(false) },
            )
        }.launchIn(viewLifecycleOwner.lifecycleScope)
        return binding.root
    }

    private fun initView(friend: Boolean?) {
        val chatUIData = chatViewModel.chatUIData.value
        val contact = chatUIData.contact ?: return
        binding.imageviewBotBadge.isVisible = contact.id.isOfficialAccount()

        if (contact.id == globalServices.myId || contact.id.isOfficialAccount()) {
            showActions(call = false, addContact = false, createGroup = false)
        }
        if (contact.id == globalServices.myId) binding.imageviewTitleAvatar.showFavorites() else binding.imageviewTitleAvatar.setAvatar(contact)
        if (contact.id == globalServices.myId) {
            binding.textviewNickname.text = getString(com.difft.android.base.R.string.chat_favorites)
        } else if (contact.id.isOfficialAccount()) {
            binding.textviewNickname.text = contact.getDisplayNameForUI()
        } else {
            binding.textviewNickname.text = contact.getDisplayNameForUI()

            viewLifecycleOwner.lifecycleScope.launch {
                val isFriend = friend ?: withContext(Dispatchers.IO) {
                    wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contact.id)) != null
                }
                if (!isAdded || view == null) return@launch
                
                
                val shouldHideCallButton = !isFriend || chatSettingViewModel.currentConversationSet?.blockStatus == 1
                showActions(
                    call = !shouldHideCallButton,
                    addContact = !isFriend,
                    createGroup = isFriend,
                    onCall = ::startCall,
                    onAddContact = ::requestAddFriend,
                    onCreateGroup = { openCreateGroupFromConversation(this@ChatHeaderFragment, contact.id) },
                )
            }
        }
    }

    
    private fun showActions(
        call: Boolean,
        addContact: Boolean,
        createGroup: Boolean,
        onCall: (() -> Unit)? = null,
        onAddContact: (() -> Unit)? = null,
        onCreateGroup: (() -> Unit)? = null,
    ) {
        val bar = sideChromeBar()
        bar.setAction(ACTION_CALL, binding.buttonCall, R.drawable.chat_fragment_header_call, getString(R.string.chat_action_call), call, onClick = onCall, floatInPlace = false)
        bar.setAction(ACTION_ADD_CONTACT, binding.imageviewAddContact, R.drawable.chat_icon_add_contact, getString(R.string.chat_option_add_contact), addContact, onClick = onAddContact, floatInPlace = false)
        bar.setAction(ACTION_CREATE_GROUP, binding.imageviewCreateGroup, R.drawable.chat_icon_create_group, getString(R.string.chat_option_create_group), createGroup, onClick = onCreateGroup, floatInPlace = false)
    }

    private fun startCall() {
        
        viewLifecycleOwner.lifecycleScope.launch {
            val chatRoomName = withContext(Dispatchers.IO) {
                chatViewModel.chatUIData.value.contact?.getDisplayNameForUI() ?: ""
            }
            val act = activity ?: return@launch
            if (!isAdded || view == null) return@launch
            chatViewModel.startCall(act, chatRoomName)
        }
    }

    private fun requestAddFriend() {
        val (sourceType, source) = ChatFragment.conversationSourceOf(this)
        pageWait.show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ContactorUtil.fetchAddFriendRequest(requireContext(), chatViewModel.forWhat.id, sourceType, source)
                }
                if (!isAdded || view == null) return@launch
                pageWait.dismiss()
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
                pageWait.dismiss()
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }

    private fun updateArchiveTimeUI(archiveTime: Long) {
        bindCompactExpiry(binding.timerRow, binding.textviewTimer, archiveTime)
    }

    private companion object {
        const val ACTION_CALL = "call"
        const val ACTION_ADD_CONTACT = "add_contact"
        const val ACTION_CREATE_GROUP = "create_group"
    }
}
