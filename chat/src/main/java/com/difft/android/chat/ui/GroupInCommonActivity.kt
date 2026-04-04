package com.difft.android.chat.ui


import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity

import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import com.difft.android.base.widget.ChativePopupView
import com.difft.android.base.widget.ChativePopupWindow
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivityGroupInCommonBinding
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.group.AddOrRemoveMembersReq
import com.difft.android.network.group.GroupRepo
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import org.difft.app.database.WCDB
import org.difft.app.database.models.GroupModel
import javax.inject.Inject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.base.log.lumberjack.L
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay


@AndroidEntryPoint
class GroupInCommonActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity, contactId: String) {
            val intent = Intent(activity, GroupInCommonActivity::class.java)
            intent.putExtra("id", contactId)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityGroupInCommonBinding by viewbind()

    private val contactId: String? by lazy {
        intent.getStringExtra("id")
    }

    val myID: String by lazy {
        globalServices.myId
    }

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var wcdb: WCDB

    private var searchJob: Job? = null

    private val mGroupsAdapter: GroupInCommonAdapter by lazy {
        object : GroupInCommonAdapter() {
            override fun onItemClick(group: GroupModel) {
                GroupChatContentActivity.startActivity(this@GroupInCommonActivity, group.gid)
            }

            override fun onItemLongClick(itemView: View, group: GroupModel, touchX: Int, touchY: Int) {
                showItemActionsPop(itemView, group, touchX, touchY)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.recyclerviewGroup.apply {
            layoutManager = LinearLayoutManager(this@GroupInCommonActivity)
            adapter = mGroupsAdapter
            itemAnimator = null
        }

        mBinding.edittextSearchInput.addTextChangedListener {
            // Cancel previous search job
            searchJob?.cancel()

            // Create new debounced search job
            searchJob = lifecycleScope.launch {
                delay(300)
                // Pass null to let searchGroups get the latest text value
                searchGroups()
            }
        }

        mBinding.buttonClear.setOnClickListener {
            // Cancel any pending search
            searchJob?.cancel()

            mBinding.edittextSearchInput.text = null
            // Immediately search for all groups when clearing
            searchGroups()
            // Scroll to top when clearing search
            mBinding.recyclerviewGroup.scrollToPosition(0)
        }

        mBinding.buttonClear.animate().apply {
            val etContent = mBinding.edittextSearchInput.text.toString().trim()
            cancel()
            val toAlpha = if (!TextUtils.isEmpty(etContent)) 1.0f else 0f
            alpha(toAlpha)
        }

        groupUtil.singleGroupsUpdate
            .onEach { mBinding.edittextSearchInput.text = null }
            .catch { L.w { "[GroupInCommonActivity] observe singleGroupsUpdate error: ${it.stackTraceToString()}" } }
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .launchIn(lifecycleScope)

        searchGroups()
    }

    private fun searchGroups() {
        lifecycleScope.launch {
            try {
                // Get the latest text value at search time
                val searchQuery = mBinding.edittextSearchInput.text.toString().trim()

                val commonGroups = withContext(Dispatchers.IO) {
                    // Efficient approach: directly query for groups where both users are members
                    val contactGroups = wcdb.groupMemberContactor.getAllObjects(
                        DBGroupMemberContactorModel.id.eq(contactId)
                    ).map { it.gid }.distinct()

                    if (contactGroups.isEmpty()) {
                        return@withContext emptyList()
                    }

                    val myGroups = wcdb.groupMemberContactor.getAllObjects(
                        DBGroupMemberContactorModel.id.eq(globalServices.myId)
                    ).map { it.gid }.distinct()

                    val commonGroupIds = contactGroups.intersect(myGroups.toSet()).filter { it.matches(Regex("^[0-9a-fA-F]+$")) }.distinct()

                    if (commonGroupIds.isEmpty()) {
                        return@withContext emptyList()
                    }

                    // Build query with search condition if provided
                    val baseQuery = DBGroupModel.gid.`in`(*commonGroupIds.toTypedArray())
                        .and(DBGroupModel.status.eq(0))

                    val finalQuery = if (searchQuery.isNotEmpty()) {
                        baseQuery.and(DBGroupModel.name.upper().like("%${searchQuery.uppercase()}%"))
                    } else {
                        baseQuery
                    }

                    // Get full group objects for common groups with search filter
                    wcdb.group.getAllObjects(finalQuery)
                }

                // Update UI on main thread
                mGroupsAdapter.submitList(commonGroups) {
                    // Scroll to top after data is updated
                    mBinding.recyclerviewGroup.scrollToPosition(0)
                }

            } catch (e: Exception) {
                L.e { "[GroupInCommonActivity] Error searching common groups: ${e.stackTraceToString()}" }
                // Fallback: show empty list on error
                mGroupsAdapter.submitList(emptyList())
            }
        }
    }

    private var popupWindow: PopupWindow? = null
    private fun showItemActionsPop(rootView: View, group: GroupModel, touchX: Int, touchY: Int) {
        if (group.status != 0) return
        lifecycleScope.launch {
            val members = withContext(Dispatchers.IO) { group.members }
            val myRole = members.find { it.id == myID }?.groupRole ?: return@launch
            val errorColor = ContextCompat.getColor(this@GroupInCommonActivity, com.difft.android.base.R.color.error)

            val itemList = mutableListOf<ChativePopupView.Item>().apply {
                if (myRole == GROUP_ROLE_OWNER) {
                    add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_disband_new), getString(R.string.group_disband), errorColor) {
                        disbandGroup(group)
                        popupWindow?.dismiss()
                    })
                } else {
                    add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_leave), getString(R.string.group_leave), errorColor) {
                        leaveGroup(group)
                        popupWindow?.dismiss()
                    })
                }

                val contactMember = contactId?.let { cid -> members.find { it.id == cid } }
                if (contactMember != null) {
                    val contactRole = contactMember.groupRole ?: GROUP_ROLE_MEMBER
                    val canRemove = myRole < contactRole ||
                        (group.anyoneRemove == true && contactRole == GROUP_ROLE_MEMBER && contactId != myID)
                    if (canRemove) {
                        add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_remove_member), getString(R.string.group_remove_member_action), errorColor) {
                            removeMemberFromGroup(group)
                            popupWindow?.dismiss()
                        })
                    }
                }
            }
            popupWindow = ChativePopupWindow.showAtTouchPosition(rootView, itemList, touchX, touchY)
        }
    }

    private fun leaveGroup(group: GroupModel) {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.group_leave),
            message = getString(R.string.group_leave_notice),
            confirmText = getString(R.string.group_leave_leave),
            cancelText = getString(R.string.group_leave_cancel),
            cancelable = false,
            onConfirm = {
                lifecycleScope.launch {
                    ComposeDialogManager.showWait(this@GroupInCommonActivity)
                    try {
                        val response = withContext(Dispatchers.IO) {
                            groupRepo.leaveGroup(group.gid, AddOrRemoveMembersReq(mutableListOf(myID)))
                        }
                        if (!response.isSuccess()) {
                            ToastUtil.show(response.reason ?: getString(R.string.operation_failed))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.w { "[GroupInCommonActivity] leaveGroup error: ${e.stackTraceToString()}" }
                        ToastUtil.show(R.string.chat_net_error)
                    } finally {
                        ComposeDialogManager.dismissWait()
                    }
                }
            }
        )
    }

    private fun disbandGroup(group: GroupModel) {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.group_disband),
            message = getString(R.string.group_disband_tips),
            confirmText = getString(R.string.group_disband_disband),
            cancelText = getString(R.string.group_leave_cancel),
            cancelable = false,
            onConfirm = {
                lifecycleScope.launch {
                    ComposeDialogManager.showWait(this@GroupInCommonActivity)
                    try {
                        val response = withContext(Dispatchers.IO) {
                            groupRepo.deleteGroup(group.gid)
                        }
                        if (!response.isSuccess()) {
                            ToastUtil.show(response.reason ?: getString(R.string.operation_failed))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.w { "[GroupInCommonActivity] disbandGroup error: ${e.stackTraceToString()}" }
                        ToastUtil.show(R.string.chat_net_error)
                    } finally {
                        ComposeDialogManager.dismissWait()
                    }
                }
            }
        )
    }

    private fun removeMemberFromGroup(group: GroupModel) {
        val cid = contactId ?: return
        lifecycleScope.launch {
            val displayName = withContext(Dispatchers.IO) {
                wcdb.groupMemberContactor.getFirstObject(
                    DBGroupMemberContactorModel.gid.eq(group.gid)
                        .and(DBGroupMemberContactorModel.id.eq(cid))
                )?.displayName ?: cid
            }
            ComposeDialogManager.showMessageDialog(
                context = this@GroupInCommonActivity,
                title = getString(R.string.group_remove_member_title, displayName),
                message = getString(R.string.group_remove_member_body),
                confirmText = getString(R.string.group_remove_member_confirm),
                cancelText = getString(R.string.group_leave_cancel),
                cancelable = false,
                onConfirm = {
                    lifecycleScope.launch {
                        ComposeDialogManager.showWait(this@GroupInCommonActivity)
                        try {
                            val response = withContext(Dispatchers.IO) {
                                groupRepo.removeMembers(group.gid, listOf(cid))
                            }
                            if (!response.isSuccess()) {
                                ToastUtil.show(response.reason ?: getString(R.string.operation_failed))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            L.w { "[GroupInCommonActivity] removeMember error: ${e.stackTraceToString()}" }
                            ToastUtil.show(R.string.chat_net_error)
                        } finally {
                            ComposeDialogManager.dismissWait()
                        }
                    }
                }
            )
        }
    }
}