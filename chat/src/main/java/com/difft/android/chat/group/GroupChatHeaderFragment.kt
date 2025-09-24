package com.difft.android.chat.group

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import com.difft.android.chat.R
import com.difft.android.chat.common.header.CommonHeaderFragment
import com.difft.android.chat.databinding.ChatFragmentGroupHeaderBinding
import com.difft.android.chat.setting.archive.MessageArchiveUtil
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.ui.ChatMessageViewModel
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.kongzue.dialogx.dialogs.PopTip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asObservable
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupModel
import javax.inject.Inject


@AndroidEntryPoint
class GroupChatHeaderFragment : CommonHeaderFragment() {

    private lateinit var binding: ChatFragmentGroupHeaderBinding

    private val chatViewModel: ChatMessageViewModel by activityViewModels()

    @Inject
    lateinit var userManager: UserManager

    private var mGroup: GroupModel? = null
    private var role = GROUP_ROLE_MEMBER

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = ChatFragmentGroupHeaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("AutoDispose")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chatViewModel.chatUIData.asObservable()
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ chatUIData ->
                val group = chatUIData.group ?: return@subscribe
                mGroup = group
                role = group.members.find { it.id == globalServices.myId }?.groupRole ?: GROUP_ROLE_MEMBER
                if (TextUtils.isEmpty(group.name)) {
                    binding.title.text = getString(R.string.group_unknown_group)
                } else {
                    binding.title.text = group.name
                }
                if (group.status == 3 || TextUtils.isEmpty(group.name)) {
                    binding.textviewGroupMemberCount.visibility = View.GONE
                } else {
                    binding.textviewGroupMemberCount.visibility = View.VISIBLE
                    val size = "(${group.members.size})"
                    binding.textviewGroupMemberCount.text = size
                }

                if (group.status == 0) {
//                    binding.imageviewMenuMore.visibility = View.VISIBLE
                    binding.imageviewAddMember.visibility = View.VISIBLE
                    binding.imageviewAddMember.setOnClickListener {
                        if (role < GROUP_ROLE_MEMBER || group.invitationRule == 2) {
                            gotoMembersActivity(group.gid)
                        } else {
                            PopTip.show(getString(R.string.group_permission_denied))
                        }
                    }

                    binding.buttonCall.setOnClickListener {
                        if (!GroupUtil.canSpeak(group, globalServices.myId)) {
                            PopTip.show(getString(R.string.group_only_moderators_can_speak_tip))
                            return@setOnClickListener
                        }
                        chatViewModel.startCall(requireActivity(), group.name)
                    }
                } else {
//                    binding.imageviewMenuMore.visibility = View.GONE
                    binding.imageviewAddMember.visibility = View.GONE
                }
            }, {
                binding.title.text = null
                binding.textviewGroupMemberCount.text = null

                it.printStackTrace()
            })

        binding.ibBack.setOnClickListener {
            activity?.finish()
        }
        binding.title.setOnClickListener {
            (activity as GroupChatContentActivity).openGroupInfoActivity()
        }

        chatViewModel.showReactionShade
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it) binding.reactionsShade.visibility = View.VISIBLE else binding.reactionsShade.visibility = View.GONE
            }, {})

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
            }, {})

        chatViewModel.chatMessageListUIState.onEach {

            if (it.chatMessages.any { it.editMode }) {
                binding.ibBack.setImageResource(R.drawable.chat_icon_close)
                binding.ibBack.setOnClickListener { chatViewModel.selectModel(false) }
            } else {
                binding.ibBack.setImageResource(R.mipmap.chat_tabler_arrow_left)
                binding.ibBack.setOnClickListener { activity?.finish() }
            }

        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun gotoMembersActivity(groupId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 只获取成员ID，而不是完整的成员对象
            val memberIds = mGroup?.members?.mapNotNull { it.id } ?: emptyList()

            withContext(Dispatchers.Main) {
                val intent = Intent(requireContext(), GroupSelectMemberActivity::class.java).apply {
                    putExtra(GroupSelectMemberActivity.EXTRA_TYPE, GroupSelectMemberActivity.TYPE_ADD_MEMBER)
                    putStringArrayListExtra(GroupSelectMemberActivity.EXTRA_SELECTED_MEMBER_IDS, ArrayList(memberIds))
                    putExtra(GroupSelectMemberActivity.EXTRA_GID, groupId)
                }
                startActivity(intent)
            }
        }
    }
}