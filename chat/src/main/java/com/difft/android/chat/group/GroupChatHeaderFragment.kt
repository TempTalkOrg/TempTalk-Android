package com.difft.android.chat.group

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.viewModels
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.utils.DualPaneUtils.setupDynamicBackButton
import com.difft.android.base.ui.sidechrome.sideChromeBar
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.chat.R
import com.difft.android.chat.common.header.CommonHeaderFragment
import com.difft.android.chat.databinding.ChatFragmentGroupHeaderBinding
import com.difft.android.chat.setting.archive.bindCompactExpiry
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.usesConversationGlass
import com.difft.android.chat.ui.ChatHeaderChrome
import com.difft.android.chat.ui.ChatMessageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject

@AndroidEntryPoint
class GroupChatHeaderFragment : CommonHeaderFragment() {

    @Inject
    lateinit var groupUtil: GroupUtil

    private lateinit var binding: ChatFragmentGroupHeaderBinding

    
    
    
    private val chatViewModel: ChatMessageViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )
    private val chatSettingViewModel: ChatSettingViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = ChatFragmentGroupHeaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    
    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ChatHeaderChrome.apply(
            this, binding.ibBack, binding.titlePill, binding.imageviewTitleAvatar, binding.titleText,
            binding.nameRow, binding.timerRow, binding.actionsCapsule,
        )
        ChatHeaderChrome.applyCard(binding.composeViewPinnedMessages, glass = usesConversationGlass())
        
        showActions(call = binding.buttonCall.visibility == View.VISIBLE, addMember = false)

        chatViewModel.chatUIData.onEach { chatUIData ->
            val group = chatUIData.group ?: return@onEach

            if (!isAdded || view == null) return@onEach
            binding.imageviewTitleAvatar.setAvatar(group.getDisplayAvatarData(), gid = group.gid)

            if (group.status == 0) {
                val (memberCount, currentUserRole) = withContext(Dispatchers.IO) {
                    val members = wcdb.groupMemberContactor.getAllObjects(
                        DBGroupMemberContactorModel.gid.eq(group.gid)
                    )
                    val role = members.find { it.id == globalServices.myId }?.groupRole ?: GROUP_ROLE_MEMBER
                    Pair(members.size, role)
                }

                if (TextUtils.isEmpty(group.name)) {
                    binding.title.text = getString(R.string.group_unknown_group)
                } else {
                    binding.title.text = group.name
                }

                binding.textviewGroupMemberCount.text = "($memberCount)"
                binding.textviewGroupMemberCount.visibility = View.VISIBLE

                showActions(
                    call = true,
                    addMember = true,
                    onCall = {
                        if (!groupUtil.canSpeak(group, globalServices.myId)) {
                            ToastUtil.show(getString(R.string.group_only_moderators_can_speak_tip))
                        } else {
                            chatViewModel.startCall(requireActivity(), group.name)
                        }
                    },
                    onAddMember = {
                        if (canInviteGroupMembers(currentUserRole, group.invitationRule)) {
                            gotoMembersActivity(group.gid ?: "")
                        } else {
                            ToastUtil.show(getString(R.string.group_permission_denied))
                        }
                    },
                )

                binding.titleContainerInner.setOnClickListener {
                    (parentFragment as? GroupChatFragment)?.openGroupInfoActivity()
                        ?: (activity as? GroupChatContentActivity)?.openGroupInfoActivity()
                }
            } else {
                
                
                if (!TextUtils.isEmpty(group.name)) {
                    binding.title.text = group.name
                }
                binding.textviewGroupMemberCount.visibility = View.GONE
                showActions(call = false, addMember = false)
                binding.timerRow.visibility = View.GONE
                binding.titleContainerInner.setOnClickListener(null)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.ibBack.setOnClickListener {
            detailNavigation().pop(this)
        }

        
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach {
                updateArchiveTimeUI(it.messageExpiry)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        
        chatViewModel.selectMessagesState.onEach {
            setupDynamicBackButton(
                backButton = binding.ibBack,
                isEditMode = it.editModel,
                backIconRes = com.difft.android.base.R.drawable.chative_ic_back,
                closeIconRes = R.drawable.chat_ic_close_24,
                onExitEditMode = { chatViewModel.selectModel(false) },
            )
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    
    private fun showActions(call: Boolean, addMember: Boolean, onCall: (() -> Unit)? = null, onAddMember: (() -> Unit)? = null) {
        val bar = sideChromeBar()
        bar.setAction(ACTION_CALL, binding.buttonCall, R.drawable.chat_fragment_header_call, getString(R.string.chat_action_call), call, onClick = onCall, floatInPlace = false)
        bar.setAction(ACTION_ADD_MEMBER, binding.imageviewAddMember, R.drawable.chat_icon_create_group, getString(R.string.group_add_member), addMember, onClick = onAddMember, floatInPlace = false)
    }

    private fun gotoMembersActivity(groupId: String) {
        detailNavigation().push(this, GroupSelectMemberDestination(groupId,
            com.difft.android.chat.group.mvi.GroupPageContract.MemberAction.ADD))
    }

    private fun updateArchiveTimeUI(archiveTime: Long) {
        bindCompactExpiry(binding.timerRow, binding.textviewTimer, archiveTime)
    }

    private companion object {
        const val ACTION_CALL = "call"
        const val ACTION_ADD_MEMBER = "add_member"
    }
}
