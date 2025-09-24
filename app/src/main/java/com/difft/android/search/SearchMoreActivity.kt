package com.difft.android.search


import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.RxUtil
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
import io.reactivex.rxjava3.core.Single
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
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

    private val type: Int by lazy {
        intent.getIntExtra("type", -1)
    }
    private val key: String by lazy {
        intent.getStringExtra("key") ?: ""
    }
    private val mContactsAdapter: SearchContactAdapter by lazy {
        object : SearchContactAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                ContactDetailActivity.startActivity(this@SearchMoreActivity, contact.id)
            }
        }.apply {
            setOrUpdateSearchKey(key)
        }
    }

    private val mGroupsAdapter: SearchGroupsAdapter by lazy {
        object : SearchGroupsAdapter() {
            override fun onItemClick(group: GroupUIData) {
                GroupChatContentActivity.startActivity(this@SearchMoreActivity, group.gid)
            }
        }.apply {
            setOrUpdateSearchKey(key)
        }
    }

    private val mSearchChatHistoryAdapter: SearchChatHistoryAdapter by lazy {
        object : SearchChatHistoryAdapter() {
            override fun onItemClicked(data: SearchChatHistoryViewData, position: Int) {
                if (data.onlyOneResult) {
                    if (data.type == SearchChatHistoryViewData.Type.Group) {
                        GroupChatContentActivity.startActivity(this@SearchMoreActivity, data.conversationId, jumpMessageTimeStamp = data.messageTimeStamp)
                    } else {
                        ChatActivity.startActivity(this@SearchMoreActivity, data.conversationId, jumpMessageTimeStamp = data.messageTimeStamp)
                    }
                } else {
                    SearchMessageActivity.startActivity(this@SearchMoreActivity, data.conversationId, data.type == SearchChatHistoryViewData.Type.Group, key)
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
            this.layoutManager = LinearLayoutManager(this@SearchMoreActivity)
            itemAnimator = null
        }

        if (type == SEARCH_TYPE_CONTACT) {
            mBinding.tvTitle.text = getString(R.string.search_more_contacts)

            mBinding.recyclerviewResult.adapter = mContactsAdapter
            Single.fromCallable {
                wcdb.contactor.search(key)
            }.compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe({
                    mContactsAdapter.submitList(it)
                }, {
                    mContactsAdapter.submitList(emptyList())
                    it.printStackTrace()
                })
        } else if (type == SEARCH_TYPE_GROUP) {
            mBinding.tvTitle.text = getString(R.string.search_more_groups)

            mBinding.recyclerviewResult.adapter = mGroupsAdapter

            Single.just(
                wcdb.group.searchByNameAndGroupMembers(key)
                    .map { GroupUtil.convert(it) }).compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(this)).subscribe({
                    mGroupsAdapter.submitList(it)
                }, {
                    mGroupsAdapter.submitList(emptyList())
                    it.printStackTrace()
                })
        } else if (type == SEARCH_TYPE_MESSAGE) {
            mBinding.tvTitle.text = getString(R.string.search_more_messages)

            mBinding.recyclerviewResult.adapter = mSearchChatHistoryAdapter

            Single.fromCallable {
                wcdb.message.getAllObjects(
                    DBMessageModel.messageText.upper().like("%${key.uppercase()}%")
                )
                    .groupBy { wcdb.room.getFirstObject(DBRoomModel.roomId.eq(it.roomId)) }.filter {
                        it.key != null
                    }.map { it.key!! to it.value }.toMap()
            }.concatMap { result ->
                SearchUtils.createSearchChatHistoryViewDataList(this@SearchMoreActivity, result)
            }
                .compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe({
                    mSearchChatHistoryAdapter.submitList(it)
                }, {
                    mSearchChatHistoryAdapter.submitList(emptyList())
                    it.printStackTrace()
                })
        }
    }
}