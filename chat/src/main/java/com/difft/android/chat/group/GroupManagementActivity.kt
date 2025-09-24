package com.difft.android.chat.group

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.widget.Switch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RxUtil
import org.difft.app.database.convertToContactorModels
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.difft.app.database.members
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.databinding.ActivityGroupManagementBinding
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.GroupRepo
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.WaitDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupModel
import javax.inject.Inject

@SuppressLint("UseSwitchCompatOrMaterialCode")
@AndroidEntryPoint
class GroupManagementActivity : BaseActivity() {

    @Inject
    lateinit var groupRepo: GroupRepo

    private var groupInfo: GroupModel? = null

    companion object {
        fun startActivity(activity: Activity, groupId: String) {
            val intent = Intent(activity, GroupManagementActivity::class.java)
            intent.putExtra("groupId", groupId)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityGroupManagementBinding by viewbind()
    private val groupId: String by lazy {
        intent.getStringExtra("groupId") ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }

        if (TextUtils.isEmpty(groupId)) return

        GroupUtil.getSingleGroupInfo(this, groupId)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.isPresent) {
                    groupInfo = it.get()
                    initView(it.get())
                }
            }, { it.printStackTrace() })

        GroupUtil.singleGroupsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.gid == groupId) {
                    groupInfo = it
                    initView(it)
                }
            }, { it.printStackTrace() })
    }

    private val mGroupsAdapter: GroupInfoMemberAdapter by lazy {
        object : GroupInfoMemberAdapter() {
            override fun onItemClick(contact: GroupMemberModel) {
                when (contact.uid) {
                    "+" -> {
                        GroupModeratorsManagementActivity.startActivity(this@GroupManagementActivity, groupId, ActionType.ADD.action)
                    }

                    "-" -> {
                        GroupModeratorsManagementActivity.startActivity(this@GroupManagementActivity, groupId, ActionType.REMOVE.action)
                    }

                    else -> {
                        ContactDetailActivity.startActivity(this@GroupManagementActivity, contact.uid, sourceType = FriendSourceType.FROM_GROUP, source = groupId)
                    }
                }
            }
        }
    }

    private val groupInfoActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            finish()
        }
    }

    private fun initView(group: GroupModel) {
        mBinding.switchCanAdd.setOnCheckedChangeListener(null)
        mBinding.switchCanSpeak.setOnCheckedChangeListener(null)
        mBinding.switchEnableLink.setOnCheckedChangeListener(null)

        mBinding.switchCanAdd.isChecked = group.invitationRule == 1
        mBinding.switchCanSpeak.isChecked = group.publishRule == 1
        mBinding.switchEnableLink.isChecked = group.linkInviteSwitch == true

        setCheckChangeListener()

        mBinding.rvModerators.apply {
            layoutManager = GridLayoutManager(this@GroupManagementActivity, 5)
            adapter = mGroupsAdapter
            itemAnimator = null
        }

        mBinding.clTransferOwner.setOnClickListener {
            groupInfoActivityLauncher.launch(
                Intent(this, GroupModeratorsManagementActivity::class.java).apply {
                    this.putExtra("groupId", groupId)
                    this.putExtra("actionType", ActionType.TRANSFER.action)
                }
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val members = groupInfo?.members?.convertToContactorModels() ?: return@launch
            val moderators = mutableListOf<GroupMemberModel>()
            val owner = members.find { it.groupMemberContactor?.groupRole == GROUP_ROLE_OWNER }
            owner?.let {
                val contactAvatar = it.avatar?.getContactAvatarData()
                moderators.add(GroupMemberModel(it.getDisplayNameForUI(), it.id, contactAvatar?.getContactAvatarUrl(), contactAvatar?.encKey, it.getDisplayNameForUI().getSortLetter(), GROUP_ROLE_OWNER))
            }
            val otherModerators = members.filter { it.groupMemberContactor?.groupRole == GROUP_ROLE_ADMIN }
            otherModerators.forEach {
                val contactAvatar = it.avatar?.getContactAvatarData()
                moderators.add(GroupMemberModel(it.getDisplayNameForUI(), it.id, contactAvatar?.getContactAvatarUrl(), contactAvatar?.encKey, it.getDisplayNameForUI().getSortLetter(), GROUP_ROLE_ADMIN))
            }
            if (moderators.size < members.size) { //所有成员都是管理员，则不显示
                moderators.add(GroupMemberModel("", "+", "", "", ""))
            }
            if (moderators.filterNot { it.uid == "+" }.size > 1) { //有管理员，才显示
                moderators.add(GroupMemberModel("", "-", "", "", ""))
            }
            withContext(Dispatchers.Main) {
                mGroupsAdapter.submitList(moderators)
            }
        }
    }

    private fun setCheckChangeListener() {
        mBinding.switchCanAdd.setOnCheckedChangeListener { _, isChecked ->
            val invitationRule = if (isChecked) 1 else 2
            changeGroupSetting(mBinding.switchCanAdd, invitationRule = invitationRule)
        }

        mBinding.switchCanSpeak.setOnCheckedChangeListener { _, isChecked ->
            val publishRule = if (isChecked) 1 else 2
            changeGroupSetting(mBinding.switchCanSpeak, publishRule = publishRule)
        }

        mBinding.switchEnableLink.setOnCheckedChangeListener { _, isChecked ->
            changeGroupSetting(mBinding.switchEnableLink, linkInviteSwitch = isChecked)
        }
    }

    private fun changeGroupSetting(
        switch: Switch,
        invitationRule: Int? = null,
        publishRule: Int? = null,
        linkInviteSwitch: Boolean? = null
    ) {
        WaitDialog.show(this@GroupManagementActivity, "")
        groupRepo.changeGroupSettings(
            groupId, ChangeGroupSettingsReq(
                invitationRule = invitationRule,
                publishRule = publishRule,
                linkInviteSwitch = linkInviteSwitch
            )
        ).compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                WaitDialog.dismiss()
                if (it.status != 0) {
                    showErrorAndRestoreSwitch(it.reason, switch)
                }
            }, {
                it.printStackTrace()
                WaitDialog.dismiss()
                L.w { "[GroupManagement] changeGroupSetting error:" + it.stackTraceToString() }
                showErrorAndRestoreSwitch(getString(R.string.operation_failed), switch)
            })
    }

    private fun showErrorAndRestoreSwitch(errorMessage: String?, switch: Switch) {
        PopTip.show(errorMessage)

        switch.setOnCheckedChangeListener(null)
        switch.isChecked = !switch.isChecked

        setCheckChangeListener()
    }
}