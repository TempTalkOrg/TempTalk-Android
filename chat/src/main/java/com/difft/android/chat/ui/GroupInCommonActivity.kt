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
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import com.difft.android.base.widget.ChativePopupView
import com.difft.android.base.widget.ChativePopupWindow
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivityGroupInCommonBinding
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.group.AddOrRemoveMembersReq
import com.difft.android.network.group.GroupRepo
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.MessageDialog
import dagger.hilt.android.AndroidEntryPoint
import org.difft.app.database.WCDB
import org.difft.app.database.models.GroupModel
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.base.log.lumberjack.L
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBGroupMemberContactorModel
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
    lateinit var wcdb: WCDB

    private var searchJob: Job? = null

    private val mGroupsAdapter: GroupInCommonAdapter by lazy {
        object : GroupInCommonAdapter() {
            override fun onItemClick(group: GroupModel) {
                GroupChatContentActivity.startActivity(this@GroupInCommonActivity, group.gid)
            }

            override fun onItemLongClick(itemView: View, group: GroupModel) {
                showItemActionsPop(itemView, group)
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

        GroupUtil.singleGroupsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ group ->
                mBinding.edittextSearchInput.text = null
            }, {
                it.printStackTrace()
            })

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
                    
                    val commonGroupIds = contactGroups.intersect(myGroups.toSet())
                    
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
                e.printStackTrace()
                // Fallback: show empty list on error
                mGroupsAdapter.submitList(emptyList())
            }
        }
    }

    private var popupWindow: PopupWindow? = null
    private fun showItemActionsPop(rootView: View, group: GroupModel) {
        val itemList = mutableListOf<ChativePopupView.Item>().apply {
            if (group.status == 0) {
                val role = group.members.find { it.id == myID }?.groupRole
                if (role == GROUP_ROLE_OWNER) {
                    add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_disband), getString(R.string.group_disband_disband), ContextCompat.getColor(this@GroupInCommonActivity, com.difft.android.base.R.color.error)) {
                        disbandGroup(group)
                        popupWindow?.dismiss()
                    })
                } else {
                    add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_disband), getString(R.string.group_leave_leave), ContextCompat.getColor(this@GroupInCommonActivity, com.difft.android.base.R.color.error)) {
                        leaveGroup(group)
                        popupWindow?.dismiss()
                    })
                }
            }
        }
        popupWindow = ChativePopupWindow.showAsDropDown(rootView, itemList)
    }

    private fun leaveGroup(group: GroupModel) {
        MessageDialog.show(R.string.group_leave, R.string.group_leave_notice, R.string.group_leave_leave, R.string.group_leave_cancel)
            .setOkButton { dialog, v ->
                groupRepo.leaveGroup(group.gid, AddOrRemoveMembersReq(mutableListOf(myID)))
                    .compose(RxUtil.getSingleSchedulerComposer())
                    .to(RxUtil.autoDispose(this))
                    .subscribe({}, { it.printStackTrace() })
                false
            }
    }

    private fun disbandGroup(group: GroupModel) {
        MessageDialog.show(R.string.group_disband, R.string.group_disband_tips, R.string.group_disband_disband, R.string.group_leave_cancel)
            .setOkButton { dialog, v ->
                groupRepo.deleteGroup(group.gid)
                    .compose(RxUtil.getSingleSchedulerComposer())
                    .to(RxUtil.autoDispose(this))
                    .subscribe({}, { it.printStackTrace() })
                false
            }
    }
}