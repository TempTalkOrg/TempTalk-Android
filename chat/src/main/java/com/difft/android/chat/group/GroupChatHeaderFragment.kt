package com.difft.android.chat.group

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.header.CommonHeaderFragment
import com.difft.android.chat.databinding.ChatFragmentGroupHeaderBinding
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatMessageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb

@AndroidEntryPoint
class GroupChatHeaderFragment : CommonHeaderFragment() {

    private lateinit var binding: ChatFragmentGroupHeaderBinding

    private val chatViewModel: ChatMessageViewModel by activityViewModels()
    private val chatSettingViewModel: ChatSettingViewModel by activityViewModels()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = ChatFragmentGroupHeaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatViewModel.chatUIData.onEach { chatUIData ->
            val group = chatUIData.group ?: return@onEach

            // 在子线程中查询成员信息
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

            val size = "($memberCount)"
            binding.textviewGroupMemberCount.text = size

            if (group.status == 0) {
                binding.imageviewAddMember.visibility = View.VISIBLE
                binding.imageviewAddMember.setOnClickListener {
                    if (currentUserRole < GROUP_ROLE_MEMBER || group.invitationRule == 2) {
                        gotoMembersActivity(group.gid)
                    } else {
                        ToastUtil.show(getString(R.string.group_permission_denied))
                    }
                }

                binding.buttonCall.setOnClickListener {
                    if (!GroupUtil.canSpeak(group, globalServices.myId)) {
                        ToastUtil.show(getString(R.string.group_only_moderators_can_speak_tip))
                        return@setOnClickListener
                    }
                    chatViewModel.startCall(requireActivity(), group.name)
                }
            } else {
                binding.imageviewAddMember.visibility = View.GONE
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.ibBack.setOnClickListener {
            activity?.finish()
        }

        // 给整个 header 区域添加点击事件，跳转到群组详情页面
        binding.titleContainerInner.setOnClickListener {
            (activity as GroupChatContentActivity).openGroupInfoActivity()
        }

        chatViewModel.showReactionShade
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                binding.reactionsShade.visibility = if (it) View.VISIBLE else View.GONE
            }, {})

        // 统一订阅 conversationSet，处理配置相关的 UI 更新
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach {
                updateArchiveTimeUI(it.messageExpiry)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        // 观察选择状态来切换返回按钮行为
        chatViewModel.selectMessagesState.onEach {
            if (it.editModel) {
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
            val memberIds = wcdb.groupMemberContactor.getOneColumnString(
                DBGroupMemberContactorModel.id,
                DBGroupMemberContactorModel.gid.eq(groupId)
            )

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