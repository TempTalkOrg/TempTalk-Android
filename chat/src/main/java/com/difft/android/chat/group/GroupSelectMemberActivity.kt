package com.difft.android.chat.group

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L

import org.difft.app.database.convertToContactorModels
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import org.difft.app.database.search
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsall.GroupMemberRoleComparator
import com.difft.android.chat.contacts.contactsall.PinyinComparator2
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getFirstLetter
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatActivityGroupSelectMemberBinding
import com.difft.android.network.group.GroupRepo
import com.difft.android.base.widget.sideBar.GroupMemberDecoration
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupModel
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import java.util.Collections
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class GroupSelectMemberActivity : BaseActivity() {
    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_GID = "extra_gid"
        const val EXTRA_SELECTED_MEMBER_IDS = "extra_selected_member_ids"

        // 活动类型常量
        const val TYPE_REMOVE_MEMBER = 0
        const val TYPE_ADD_MEMBER = 1
        const val TYPE_CALL_ADD_MEMBER = 2
    }

    // 存储已选中的成员ID数组（初始默认选择，checkbox无法操作）
    private var selectedMemberIds: List<String>? = null

    // 存储新增选择的成员ID列表（不包括初始默认选择的）
    private var currentSelectedIds = mutableSetOf<String>()

    private var type: Int = TYPE_REMOVE_MEMBER
    private var gid: String = ""
    private val binding: ChatActivityGroupSelectMemberBinding by viewbind()

    @Inject
    lateinit var groupRepo: GroupRepo

    val mAdapter: GroupMembersAdapter by lazy {
        object : GroupMembersAdapter(type) {
            override fun onMemberClicked(model: GroupMemberModel, position: Int) {
                ContactDetailActivity.startActivity(
                    this@GroupSelectMemberActivity,
                    model.uid,
                    sourceType = FriendSourceType.FROM_GROUP,
                    source = gid
                )
            }

            override fun onCheckBoxClicked(model: GroupMemberModel, position: Int) {
                // 更新新增选择的ID列表
                model.uid?.let { uid ->
                    if (currentSelectedIds.contains(uid)) {
                        currentSelectedIds.remove(uid)
                    } else {
                        currentSelectedIds.add(uid)
                    }

                    // 通过 adapter 的 currentList 找到对应的对象并修改
                    val adapterModel = mAdapter.currentList[position]
                    adapterModel.isSelected = currentSelectedIds.contains(uid)
                }

                mAdapter.notifyItemChanged(position)
                setupActionButton()
            }
        }
    }

    private fun setupActionButton() {
        if (currentSelectedIds.isNotEmpty()) {
            binding.doneButton.isEnabled = true
            binding.doneButton.setTextColor(
                ContextCompat.getColor(this, com.difft.android.base.R.color.t_info)
            )
        } else {
            binding.doneButton.isEnabled = false
            binding.doneButton.setTextColor(
                ContextCompat.getColor(this, com.difft.android.base.R.color.t_disable)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ChatActivityGroupSelectMemberBinding.inflate(layoutInflater).root)

        // 接收已选中的成员ID数组
        selectedMemberIds = intent.getStringArrayListExtra(EXTRA_SELECTED_MEMBER_IDS)

        type = intent.getIntExtra(EXTRA_TYPE, TYPE_REMOVE_MEMBER)
        intent.getStringExtra(EXTRA_GID)?.let { gid = it }

        binding.ibBack.setOnClickListener { finish() }

        binding.recyclerviewContacts.apply {
            this.adapter = mAdapter
            this.layoutManager = LinearLayoutManager(this@GroupSelectMemberActivity)
            itemAnimator = null
        }

        when (type) {
            TYPE_ADD_MEMBER -> {
                binding.title.text = getString(R.string.group_add_member)
                setupAddMemberMode()
            }

            TYPE_CALL_ADD_MEMBER -> {
                binding.title.text = getString(R.string.add_participants)
                setupAddMemberMode()
            }

            TYPE_REMOVE_MEMBER -> {
                binding.title.text = getString(R.string.group_remove_member)
                setupRemoveMemberMode()
            }
        }
    }

    /**
     * 设置移除成员模式（TYPE_REMOVE_MEMBER 专用）
     */
    private fun setupRemoveMemberMode() {
        binding.doneButton.setOnClickListener {
            removeMembers()
        }
        binding.doneButton.isVisible = true
        binding.clSearch.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val list = ArrayList<GroupMemberModel>()
            val groupInfo = wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))
            val myRole = groupInfo?.members?.firstOrNull { it.id == globalServices.myId }?.groupRole ?: GROUP_ROLE_MEMBER
            groupInfo?.members?.convertToContactorModels()?.forEach {
                val name: String = it.getDisplayNameForUI()
                val contactAvatar = it.avatar?.getContactAvatarData()
                val memberRole = it.groupMemberContactor?.groupRole ?: GROUP_ROLE_MEMBER
                val canOperate = myRole < memberRole || (groupInfo.anyoneRemove == true && memberRole == GROUP_ROLE_MEMBER && it.id != globalServices.myId)
                list.add(
                    GroupMemberModel(
                        name,
                        it.id,
                        contactAvatar?.getContactAvatarUrl(),
                        contactAvatar?.encKey,
                        ContactorUtil.getFirstLetter(name),
                        it.groupMemberContactor?.groupRole ?: GROUP_ROLE_MEMBER,
                        isSelected = false,
                        checkBoxEnable = true,
                        showCheckBox = canOperate
                    )
                )
            }

            withContext(Dispatchers.Main) {
                // 使用自定义比较器排序
                list.sortWith(GroupMemberRoleComparator())

                addRoleDecoration(list)
                mAdapter.submitList(list)
            }
        }
    }

    /**
     * 设置添加成员模式（TYPE_ADD_MEMBER 和 TYPE_CALL_ADD_MEMBER 通用）
     */
    private fun setupAddMemberMode() {
        binding.doneButton.setOnClickListener {
            addMembers()
        }
        binding.doneButton.isVisible = true
        binding.clSearch.visibility = View.VISIBLE
        searchContacts(null)

        binding.edittextSearchInput.addTextChangedListener {
            val etContent = binding.edittextSearchInput.text.toString().trim()
            if (!TextUtils.isEmpty(etContent)) {
                searchContacts(etContent)
            } else {
                searchContacts(null)
            }
            resetButtonClear(etContent)
        }

        binding.buttonClear.setOnClickListener {
            binding.edittextSearchInput.text = null
        }

        resetButtonClear(null)
    }


    @SuppressLint("CheckResult")
    private fun removeMembers() {
        if (currentSelectedIds.isEmpty()) {
            return
        }

        L.i { "[GroupSelectMemberActivity] currentSelectedIds:$currentSelectedIds" }

        ComposeDialogManager.showWait(this@GroupSelectMemberActivity, "")

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    groupRepo.removeMembers(gid, currentSelectedIds.toList()).blockingGet()
                }

                if (response.status == 0) {
                    // 移除成员成功后，刷新群组信息
                    try {
                        withContext(Dispatchers.IO) {
                            GroupUtil.fetchAndSaveSingleGroupInfo(ApplicationDependencies.getApplication(), gid, true).blockingFirst()
                        }
                    } catch (e: Exception) {
                        L.e { "[GroupSelectMemberActivity] Failed to refresh group info after removing members: ${e.message}" }
                    }
                }

                ComposeDialogManager.dismissWait()
                if (response.status == 0) {
                    finish()
                } else {
                    response.reason?.let { ToastUtil.showLong(it) }
                }
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                e.printStackTrace()
                ToastUtil.showLong(R.string.chat_net_error)
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun addMembers() {
        if (currentSelectedIds.isEmpty()) {
            return
        }
        L.i { "[GroupSelectMemberActivity] currentSelectedIds:$currentSelectedIds" }
        when (type) {
            TYPE_CALL_ADD_MEMBER -> {
                // 返回默认已选择的 + 新选择的ID列表
                val allSelectedIds = mutableListOf<String>()
                selectedMemberIds?.let { allSelectedIds.addAll(it) }
                allSelectedIds.addAll(currentSelectedIds)

                val intent = Intent().apply {
                    putStringArrayListExtra("selected_member_ids", ArrayList(allSelectedIds))
                }
                setResult(RESULT_OK, intent)
                finish()
                return
            }

            TYPE_ADD_MEMBER -> {
                ComposeDialogManager.showWait(this@GroupSelectMemberActivity, "")

                lifecycleScope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            groupRepo.addMembers(gid, currentSelectedIds.toList()).blockingGet()
                        }

                        when (response.status) {
                            0 -> {
                                // 添加成员成功后，刷新群组信息
                                try {
                                    withContext(Dispatchers.IO) {
                                        GroupUtil.fetchAndSaveSingleGroupInfo(ApplicationDependencies.getApplication(), gid, true).blockingFirst()
                                    }
                                } catch (e: Exception) {
                                    L.e { "[GroupSelectMemberActivity] Failed to refresh group info after adding members: ${e.message}" }
                                }
                            }
                        }

                        ComposeDialogManager.dismissWait()
                        when (response.status) {
                            0 -> finish()
                            2 -> ToastUtil.showLong(getString(R.string.invite_only_moderators_can_add))
                            10125 -> {
                                response.data?.strangers?.let { strangers ->
                                    val content = strangers.joinToString(separator = ", ") { s -> s.name.toString() }
                                    ToastUtil.show(getString(R.string.group_not_your_friend))
                                }
                            }

                            else -> response.reason?.let { ToastUtil.showLong(it) }
                        }
                    } catch (e: Exception) {
                        ComposeDialogManager.dismissWait()
                        e.printStackTrace()
                        ToastUtil.showLong(R.string.chat_net_error)
                    }
                }
            }
        }
    }

    private fun addRoleDecoration(it: List<GroupMemberModel>) {
        val decoration = GroupMemberDecoration(this, object : GroupMemberDecoration.DecorationCallback {
            override fun getGroupId(position: Int): Long {
                val role = it[position].role
                return if (role < GROUP_ROLE_MEMBER) {
                    GROUP_ROLE_ADMIN.toLong()
                } else {
                    GROUP_ROLE_MEMBER.toLong()
                }
            }

            override fun getGroupRoleText(position: Int): String {
                return if (it[position].role == GROUP_ROLE_MEMBER) {
                    getString(R.string.group_participants)
                } else {
                    getString(R.string.group_moderators)
                }
            }
        })
        binding.recyclerviewContacts.addItemDecoration(decoration)
    }

    private fun searchContacts(key: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contacts = if (key != null) {
                    wcdb.contactor.search(key)
                } else {
                    wcdb.contactor.allObjects
                }

                withContext(Dispatchers.Main) {
                    refreshContactsList(contacts)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun refreshContactsList(list: List<ContactorModel>) {
        // 只在添加成员模式下刷新联系人列表
        if (type != TYPE_ADD_MEMBER && type != TYPE_CALL_ADD_MEMBER) {
            return
        }

        val filteredList = list.filter {
            !it.id.isBotId()
        }

        val searchResultList = filteredList.map { contact ->
            // 判断是否在初始选择列表中（checkbox无法操作）
            val isInInitialSelection = selectedMemberIds?.contains(contact.id) ?: false
            // 判断是否在新选择列表中
            val isNewlySelected = currentSelectedIds.contains(contact.id)
            // 总的选择状态：初始选择 或 新选择
            val isCurrentlySelected = isInInitialSelection || isNewlySelected

            val contactAvatar = contact.avatar?.getContactAvatarData()

            GroupMemberModel(
                contact.getDisplayNameForUI(),
                contact.id,
                contactAvatar?.getContactAvatarUrl(),
                contactAvatar?.encKey,
                contact.getDisplayNameForUI().getFirstLetter(),
                0,
                isCurrentlySelected, // 总的选择状态
                !isInInitialSelection, // 只有不在初始选择列表中的才能操作checkbox
                true
            )
        }.let { ArrayList(it) }

        searchResultList.let {
            Collections.sort(it, PinyinComparator2())
            mAdapter.submitList(it)
        }
    }

    private fun resetButtonClear(etContent: String?) {
        binding.buttonClear.animate().apply {
            cancel()
            val toAlpha = if (!TextUtils.isEmpty(etContent)) 1.0f else 0f
            alpha(toAlpha)
        }
    }
}