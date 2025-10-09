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
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupModel
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
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
        ComposeDialogManager.showWait(this@GroupModeratorsManagementActivity, "")
        groupRepo.changeMemberRole(groupId, selectedIds.first(), ChangeRolepReq(role))
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                ComposeDialogManager.dismissWait()
                if (it.status == 0) {
                    ToastUtil.showLong(getString(R.string.operation_successful))
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }, {
                it.printStackTrace()
                ComposeDialogManager.dismissWait()
                it.message?.let { message -> ToastUtil.show(message) }
            })
    }

    private fun showTransferDialog() {
        val name = groupInfo?.members?.find { it.id == selectedIds.first() }?.displayName
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.tip),
            message = getString(R.string.group_transfer_owner_tips, name),
            confirmText = getString(R.string.chat_dialog_ok),
            cancelText = getString(R.string.chat_dialog_cancel),
            onConfirm = {
                transferOwner()
            }
        )
    }

    private fun transferOwner() {
        ComposeDialogManager.showWait(this@GroupModeratorsManagementActivity, "")
        groupRepo.changeGroupSettings(
            groupId, ChangeGroupSettingsReq(owner = selectedIds.first())
        )
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                ComposeDialogManager.dismissWait()
                if (it.status == 0) {
                    ToastUtil.showLong(getString(R.string.operation_successful))
                    setResult(RESULT_OK)
                    finish()
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }, {
                it.printStackTrace()
                ComposeDialogManager.dismissWait()
                it.message?.let { message -> ToastUtil.show(message) }
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
