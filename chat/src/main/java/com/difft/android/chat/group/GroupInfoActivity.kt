package com.difft.android.chat.group

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import autodispose2.androidx.lifecycle.autoDispose
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.utils.RxUtil
import org.difft.app.database.convertToContactorModels
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsall.GroupMemberRoleComparator
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.databinding.ChatActivityGroupInfoBinding
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.search.SearchMessageActivity
import com.difft.android.chat.setting.ChatArchiveSettingsActivity
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.archive.MessageArchiveUtil
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.group.AddOrRemoveMembersReq
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.responses.MuteStatus
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
const val KEY_GROUP_ID = "groupId"
const val KEY_GROUP_NAME = "groupName"
const val EXTRA_SELECTED_MEMBER_IDS = "extra_selected_member_ids"

@AndroidEntryPoint
class GroupInfoActivity : BaseActivity() {

    val binding: ChatActivityGroupInfoBinding by viewbind()
    private var role = GROUP_ROLE_MEMBER
    private var selfGroupInfo: GroupMemberContactorModel? = null
    private var groupInfo: GroupModel? = null

    private val groupId: String by lazy {
        intent.getStringExtra(KEY_GROUP_ID) ?: ""
    }

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var dbRoomStore: DBRoomStore

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var userManager: com.difft.android.base.user.UserManager

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
            it.create(For.Group(groupId))
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.ibBack.setOnClickListener { finish() }

        chatSettingViewModel.conversationSet.observeOn(AndroidSchedulers.mainThread())
            .autoDispose(this)
            .subscribe {
                L.i { "BH_Lin: settings of conversation=$it" }
                binding.switch2mute.isChecked = it.isMuted == true
            }
        chatSettingViewModel.getConversationConfigs(
            activity = this,
            conversations = arrayListOf(groupId),
        )

        binding.switch2mute.setOnClickListener {
            var muteStatus = MuteStatus.UNMUTED.value
            if (binding.switch2mute.isChecked) {
                muteStatus = MuteStatus.MUTED.value
            }
            chatSettingViewModel.setConversationConfigs(
                activity = this,
                conversation = groupId,
                muteStatus = muteStatus,
            )
        }

        binding.switchStick.isChecked = isPined()
        binding.switchStick.setOnClickListener {
            if (isPined()) {
                pinChattingRoom(false)
            } else {
                pinChattingRoom(true)
            }
        }


        GroupUtil.singleGroupsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.gid == groupId) {
                    groupInfo = it
                    selfGroupInfo = groupInfo?.members?.find { member -> member.id == globalServices.myId }
                    selfGroupInfo?.let { info ->
                        role = info.groupRole ?: GROUP_ROLE_MEMBER
                    }
                    initView()
                }
            }, { it.printStackTrace() })

        messageArchiveManager.getMessageArchiveTime(For.Group(groupId))
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                binding.disappearingTimeText.text = it.toArchiveTimeDisplayText()
            }, { it.printStackTrace() })

        MessageArchiveUtil.archiveTimeUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.first == groupId) {
                    binding.disappearingTimeText.text = it.second.toArchiveTimeDisplayText()
                }
            }, { it.printStackTrace() })

        ContactorUtil.contactsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                setMembersView()
            }, { it.printStackTrace() })

        getGroupInfo()
    }

    private fun getGroupInfo() {
        GroupUtil.getSingleGroupInfo(this, groupId)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.isPresent) {
                    groupInfo = it.get()
                    selfGroupInfo = groupInfo?.members?.find { member -> member.id == globalServices.myId }
                    selfGroupInfo?.let { info ->
                        role = info.groupRole ?: GROUP_ROLE_MEMBER
                    }
                    initView()
                }
            }, { it.printStackTrace() })
    }

    private fun initView() {
        val title = groupInfo?.name + "(" + groupInfo?.members?.size.toString() + ")"
        binding.title.text = title
        setMembersView()
        setOtherView()
    }

    private val mGroupsAdapter: GroupInfoMemberAdapter by lazy {
        object : GroupInfoMemberAdapter() {
            override fun onItemClick(contact: GroupMemberModel) {
                when (contact.uid) {
                    "+" -> {
                        if (role < GROUP_ROLE_MEMBER || groupInfo?.invitationRule == 2) {
                            gotoMembersActivity(GroupSelectMemberActivity.TYPE_ADD_MEMBER)
                        } else {
                            ToastUtil.show(getString(R.string.group_permission_denied))
                        }
                    }

                    "-" -> {
                        if (role < GROUP_ROLE_MEMBER || groupInfo?.anyoneRemove == true) {
                            gotoMembersActivity(GroupSelectMemberActivity.TYPE_REMOVE_MEMBER)
                        } else {
                            ToastUtil.show(getString(R.string.group_permission_denied))
                        }
                    }

                    else -> {
                        ContactDetailActivity.startActivity(this@GroupInfoActivity, contact.uid, sourceType = FriendSourceType.FROM_GROUP, source = groupId)
                    }
                }
            }
        }
    }

    private fun gotoMembersActivity(type: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val memberIds = groupInfo?.members?.mapNotNull { it.id } ?: emptyList()

            withContext(Dispatchers.Main) {
                val intent = Intent(this@GroupInfoActivity, GroupSelectMemberActivity::class.java).apply {
                    putExtra(GroupSelectMemberActivity.EXTRA_TYPE, type)
                    putStringArrayListExtra(EXTRA_SELECTED_MEMBER_IDS, ArrayList(memberIds))
                    putExtra(GroupSelectMemberActivity.EXTRA_GID, groupId)
                }
                startActivity(intent)
            }
        }
    }

    private val defaultDisplaySize = 13 //默认显示的人数

    private fun setMembersView() {
        binding.edittextSearchInput.setOnClickListener {
            GroupMembersSearchActivity.startActivity(this, groupId)
        }

        binding.rvMembers.apply {
            layoutManager = GridLayoutManager(this@GroupInfoActivity, 5)
            adapter = mGroupsAdapter
            itemAnimator = null
        }

        if ((groupInfo?.members?.size ?: 0) > defaultDisplaySize) {
            binding.relViewAll.visibility = View.VISIBLE
            binding.relViewAll.setOnClickListener {
//            gotoMembersActivity()
                if (binding.tvViewAll.isVisible) {
                    binding.tvViewAll.visibility = View.GONE
                    binding.tvCollapse.visibility = View.VISIBLE
                    updateMemberList(true)
                } else {
                    binding.tvViewAll.visibility = View.VISIBLE
                    binding.tvCollapse.visibility = View.GONE
                    updateMemberList(false)
                }
            }
        } else {
            binding.relViewAll.visibility = View.GONE
        }

        updateMemberList(false)
    }

    private fun updateMemberList(showAll: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val contactorModels = groupInfo?.members?.convertToContactorModels()

            contactorModels?.let {
                var members = mutableListOf<GroupMemberModel>()

                it.forEach { member ->
                    val contactAvatar = member.avatar?.getContactAvatarData()
                    members.add(
                        GroupMemberModel(
                            member.getDisplayNameForUI(),
                            member.id,
                            contactAvatar?.getContactAvatarUrl(),
                            contactAvatar?.encKey,
                            member.getDisplayNameForUI().getSortLetter(),
                            member.groupMemberContactor?.groupRole ?: GROUP_ROLE_MEMBER
                        )
                    )
                }

                members.sortWith(GroupMemberRoleComparator())

                if (!showAll && members.size > defaultDisplaySize) {
                    members = members.subList(0, defaultDisplaySize)
                }

                members.add(GroupMemberModel("", "+", "", "", ""))
                members.add(GroupMemberModel("", "-", "", "", ""))

                withContext(Dispatchers.Main) {
                    mGroupsAdapter.submitList(members)
                }
            }
        }
    }

    private fun setOtherView() {
        binding.llSearchChatHistory.setOnClickListener {
            SearchMessageActivity.startActivity(this@GroupInfoActivity, groupId, true, null)
        }

        binding.disappearingTimeContainer.setOnClickListener {
            val target = groupId
                .takeIf { it.isNotBlank() }
                ?.let { For.Group(it) } ?: return@setOnClickListener

            ChatArchiveSettingsActivity.start(this@GroupInfoActivity, target)
        }

        if (messageNotificationUtil.supportConversationNotification()) {
            binding.relNotificationSound.visibility = View.VISIBLE
            binding.relNotificationSound.setOnClickListener {
                messageNotificationUtil.createChannelForConversation(groupId, groupInfo?.name ?: groupId)
                messageNotificationUtil.openMessageNotificationChannelSettings(this, groupId)
            }
        } else {
            binding.relNotificationSound.visibility = View.GONE
        }

        val notification = if (selfGroupInfo?.useGlobal == true) {
            userManager.getUserData()?.globalNotification
        } else {
            selfGroupInfo?.notification
        }
        binding.tvNotification.text = when (notification) {
            GlobalNotificationType.ALL.value -> getString(R.string.notification_all)
            GlobalNotificationType.MENTION.value -> getString(R.string.notification_mention_only)
            GlobalNotificationType.OFF.value -> getString(R.string.notification_off)
            else -> getString(R.string.notification_all)
        }
        binding.relNotification.setOnClickListener {
            GroupNotificationSettingsActivity.start(this, groupId)
        }

//        if (groupInfo?.linkInviteSwitch == true) {
//            binding.groupLinkContainer.visibility = View.VISIBLE
//            binding.groupLinkContainer.setOnClickListener {
//                getInviteCode(selfGroupInfo?.displayName ?: "", groupInfo?.name ?: "", groupInfo?.avatar)
//            }
//        } else {
//            binding.groupLinkContainer.visibility = View.GONE
//        }

        if (role == GROUP_ROLE_OWNER) {
            binding.relGroupManagement.visibility = View.VISIBLE
            binding.relGroupManagement.setOnClickListener {
                GroupManagementActivity.startActivity(this, groupId)
            }

            binding.leaveButton.setText(R.string.group_disband)
            binding.leaveContainer.setOnClickListener {
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(R.string.group_disband),
                    message = getString(R.string.group_disband_tips),
                    confirmText = getString(R.string.group_disband_disband),
                    cancelText = getString(R.string.group_leave_cancel),
                    onConfirm = {
                        groupRepo.deleteGroup(groupId)
                            .compose(RxUtil.getSingleSchedulerComposer())
                            .to(RxUtil.autoDispose(this))
                            .subscribe({
                                if (it.status == 0) {
                                    finish()
                                }
                            }, {
                                it.printStackTrace()
                                ToastUtil.showLong(R.string.chat_net_error)
                            })
                    }
                )
            }
        } else {
            binding.relGroupManagement.visibility = View.GONE
            binding.leaveButton.setText(R.string.group_leave)
            binding.leaveContainer.setOnClickListener {
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(R.string.group_leave),
                    message = getString(R.string.group_leave_notice),
                    confirmText = getString(R.string.group_leave_leave),
                    cancelText = getString(R.string.group_leave_cancel),
                    onConfirm = {
                        groupRepo.leaveGroup(
                            groupId,
                            AddOrRemoveMembersReq(mutableListOf(globalServices.myId))
                        ).compose(RxUtil.getSingleSchedulerComposer())
                            .to(RxUtil.autoDispose(this))
                            .subscribe({
                                L.i { "BH_Lin: response leave group it.status=${it.status}" }
                                if (it.status == 0) {
                                    finish()
                                }
                            }, {
                                it.printStackTrace()
                                L.i { "BH_Lin: leave group error it=${it.message}" }
                                ToastUtil.showLong(R.string.chat_net_error)
                            })
                    }
                )
            }
        }

        binding.editGroupContainer.setOnClickListener {
            val editIntent = Intent(this, GroupEditInfoActivity::class.java).apply {
                putExtra(KEY_GROUP_ID, groupId)
                putExtra(KEY_GROUP_NAME, groupInfo?.name)
            }
            startActivity(editIntent)
        }
    }

    private fun pinChattingRoom(isPinned: Boolean) {
        dbRoomStore.updatePinnedTime(
            For.Group(groupId),
            if (isPinned) System.currentTimeMillis() else null
        )
            .compose(RxUtil.getCompletableTransformer())
            .autoDispose(this, Lifecycle.Event.ON_DESTROY)
            .subscribe({}, { it.printStackTrace() })
    }

    private fun isPined(): Boolean {
        return dbRoomStore.getPinnedTime(For.Group(groupId)).blockingGet().isPresent
    }
}