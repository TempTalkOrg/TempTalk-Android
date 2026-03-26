package com.difft.android.search

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.R
import com.difft.android.base.BaseActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.loadRoomSearchResultsByKeyword
import org.difft.app.database.search
import org.difft.app.database.searchByNameAndGroupMembers
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.search.SearchChatHistoryAdapter
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchMessageActivity
import com.difft.android.chat.search.SearchUtils
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.databinding.ActivitySearchMoreBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject

@AndroidEntryPoint
class SearchMoreActivity : BaseActivity() {

    companion object {
        const val SEARCH_TYPE_CONTACT = 1
        const val SEARCH_TYPE_GROUP = 2
        const val SEARCH_TYPE_MESSAGE = 3

        fun startActivity(activity: Activity, type: Int, key: String) {
            val intent = Intent(activity, SearchMoreActivity::class.java)
            intent.putExtra("type", type)
            intent.putExtra("key", key)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivitySearchMoreBinding by viewbind()

    @Inject
    lateinit var wcdb: WCDB

    private val type: Int by lazy { intent.getIntExtra("type", -1) }
    private val key: String by lazy { intent.getStringExtra("key") ?: "" }

    private val mContactsAdapter: SearchContactAdapter by lazy {
        object : SearchContactAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                ContactDetailActivity.startActivity(this@SearchMoreActivity, contact.id)
            }
        }.apply { setOrUpdateSearchKey(key) }
    }

    private val mGroupsAdapter: SearchGroupsAdapter by lazy {
        object : SearchGroupsAdapter() {
            override fun onItemClick(group: GroupUIData) {
                GroupChatContentActivity.startActivity(this@SearchMoreActivity, group.gid)
            }
        }.apply { setOrUpdateSearchKey(key) }
    }

    private val mSearchChatHistoryAdapter: SearchChatHistoryAdapter by lazy {
        object : SearchChatHistoryAdapter() {
            override fun onItemClicked(data: SearchChatHistoryViewData, position: Int) {
                if (data.onlyOneResult) {
                    if (data.type == SearchChatHistoryViewData.Type.Group) {
                        GroupChatContentActivity.startActivity(
                            this@SearchMoreActivity,
                            data.conversationId,
                            jumpMessageTimeStamp = data.messageTimeStamp
                        )
                    } else {
                        ChatActivity.startActivity(
                            this@SearchMoreActivity,
                            data.conversationId,
                            jumpMessageTimeStamp = data.messageTimeStamp
                        )
                    }
                } else {
                    SearchMessageActivity.startActivity(
                        this@SearchMoreActivity,
                        data.conversationId,
                        data.type == SearchChatHistoryViewData.Type.Group,
                        key
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.recyclerviewResult.apply {
            layoutManager = LinearLayoutManager(this@SearchMoreActivity)
            itemAnimator = null
        }

        when (type) {
            SEARCH_TYPE_CONTACT -> {
                mBinding.tvTitle.text = getString(R.string.search_more_contacts)
                mBinding.recyclerviewResult.adapter = mContactsAdapter
                lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { wcdb.contactor.search(key) }
                        mContactsAdapter.submitList(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mContactsAdapter.submitList(emptyList())
                        L.w { "[SearchMoreActivity] searchContacts error: ${e.stackTraceToString()}" }
                    }
                }
            }
            SEARCH_TYPE_GROUP -> {
                mBinding.tvTitle.text = getString(R.string.search_more_groups)
                mBinding.recyclerviewResult.adapter = mGroupsAdapter
                lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            wcdb.group.searchByNameAndGroupMembers(key).map { GroupUtil.convert(it) }
                        }
                        mGroupsAdapter.submitList(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mGroupsAdapter.submitList(emptyList())
                        L.w { "[SearchMoreActivity] searchGroups error: ${e.stackTraceToString()}" }
                    }
                }
            }
            SEARCH_TYPE_MESSAGE -> {
                mBinding.tvTitle.text = getString(R.string.search_more_messages)
                mBinding.recyclerviewResult.adapter = mSearchChatHistoryAdapter
                lifecycleScope.launch {
                    try {
                        val viewData = withContext(Dispatchers.IO) {
                            val results = wcdb.loadRoomSearchResultsByKeyword(key)
                            SearchUtils.createSearchChatHistoryViewDataList(this@SearchMoreActivity, results)
                        }
                        mSearchChatHistoryAdapter.updateWithSearchKey(key, viewData)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mSearchChatHistoryAdapter.updateWithSearchKey(key, emptyList())
                        L.w { "[SearchMoreActivity] searchMessages error: ${e.stackTraceToString()}" }
                    }
                }
            }
        }
    }

}