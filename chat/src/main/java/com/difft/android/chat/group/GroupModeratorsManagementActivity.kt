package com.difft.android.chat.group

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.RxUtil
import org.difft.app.database.convertToContactorModels
import org.difft.app.database.members
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivityGroupModeratorsManagementBinding
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.ChangeRolepReq
import com.difft.android.network.group.GroupRepo
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.interfaces.DialogLifecycleCallback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupModel
import javax.inject.Inject

@AndroidEntryPoint
class GroupModeratorsManagementActivity : BaseActivity() {

    @Inject
    lateinit var groupRepo: GroupRepo

    private var groupInfo: GroupModel? = null

    companion object {
        fun startActivity(activity: Activity, groupId: String, actionType: String) {
            val intent = Intent(activity, GroupModeratorsManagementActivity::class.java)
            intent.putExtra("groupId", groupId)
            intent.putExtra("actionType", actionType)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityGroupModeratorsManagementBinding by viewbind()

    private val groupId: String by lazy {
        intent.getStringExtra("groupId") ?: ""
    }

    private val actionType: ActionType by lazy {
        val actionType = intent.getStringExtra("actionType") ?: ""
        ActionType.fromAction(actionType)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }

        if (TextUtils.isEmpty(groupId)) return

        GroupUtil.getSingleGroupInfo(this, groupId, true)
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

    private var selectedIds: Set<String> = emptySet()

    private val mGroupsAdapter: GroupModeratorsAdapter by lazy {
        GroupModeratorsAdapter { ids ->
            selectedIds = ids
        }
    }

    fun initView(group: GroupModel) {

        mBinding.rvMembers.apply {
            layoutManager = LinearLayoutManager(this@GroupModeratorsManagementActivity)
            adapter = mGroupsAdapter
            itemAnimator = null
        }

        mGroupsAdapter.selectionMode = SelectionMode.SINGLE
        val members = if (actionType.isAdd()) {
            mBinding.tvTitle.text = getString(R.string.group_add_moderators)
            group.members.filter { it.groupRole == GROUP_ROLE_MEMBER }
        } else if (actionType.isRemove()) {
            mBinding.tvTitle.text = getString(R.string.group_remove_moderators)
            group.members.filter { it.groupRole == GROUP_ROLE_ADMIN }
        } else if (actionType.isTransfer()) {
            mBinding.tvTitle.text = getString(R.string.group_transfer_owner)
            group.members.filter { it.groupRole != GROUP_ROLE_OWNER }
        } else {
            emptyList()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val contactMembers = members.convertToContactorModels()
            withContext(Dispatchers.Main) {
                mGroupsAdapter.clearSelections()
                mGroupsAdapter.submitList(contactMembers)
            }
        }

        mBinding.doneButton.setOnClickListener {
            if (selectedIds.isNotEmpty()) {
                if (actionType.isAdd()) {
                    changeMemberRole(GROUP_ROLE_ADMIN)
                } else if (actionType.isRemove()) {
                    changeMemberRole(GROUP_ROLE_MEMBER)
                } else if (actionType.isTransfer()) {
                    showTransferDialog()
                }
            }
        }
    }

    private fun changeMemberRole(role: Int) {
        WaitDialog.show(this@GroupModeratorsManagementActivity, "")
        groupRepo.changeMemberRole(groupId, selectedIds.first(), ChangeRolepReq(role))
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                WaitDialog.dismiss()
                if (it.status == 0) {
                    TipDialog.show(getString(R.string.operation_successful))
                } else {
                    PopTip.show(it.reason)
                }
            }, {
                it.printStackTrace()
                WaitDialog.dismiss()
                PopTip.show(it.message)
            })
    }

    private fun showTransferDialog() {
        val name = groupInfo?.members?.find { it.id == selectedIds.first() }?.displayName
        MessageDialog.show(
            getString(R.string.tip),
            getString(R.string.group_transfer_owner_tips, name),
            getString(R.string.chat_dialog_ok),
            getString(R.string.chat_dialog_cancel)
        )
            .setOkButton { _, _ ->
                transferOwner()
                false
            }
    }

    private fun transferOwner() {
        WaitDialog.show(this@GroupModeratorsManagementActivity, "")
        groupRepo.changeGroupSettings(
            groupId, ChangeGroupSettingsReq(owner = selectedIds.first())
        )
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                WaitDialog.dismiss()
                if (it.status == 0) {
                    TipDialog.show(getString(R.string.operation_successful)).dialogLifecycleCallback =
                        object : DialogLifecycleCallback<WaitDialog?>() {
                            override fun onDismiss(dialog: WaitDialog?) {
                                setResult(RESULT_OK)
                                finish()
                            }
                        }
                } else {
                    PopTip.show(it.reason)
                }
            }, {
                it.printStackTrace()
                WaitDialog.dismiss()
                PopTip.show(it.message)
            })
    }
}

enum class ActionType(val action: String) {
    ADD("add"),
    REMOVE("remove"),
    TRANSFER("transfer");

    companion object {
        fun fromAction(action: String): ActionType {
            return entries.find { it.action == action }
                ?: throw IllegalArgumentException("Unknown action type: $action")
        }
    }

    fun isAdd() = this == ADD
    fun isRemove() = this == REMOVE
    fun isTransfer() = this == TRANSFER
}
