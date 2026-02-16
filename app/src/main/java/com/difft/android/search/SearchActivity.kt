package com.difft.android.search

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.globalServices
import org.difft.app.database.search
import org.difft.app.database.searchByNameAndGroupMembers
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.recent.ListItem
import com.difft.android.chat.recent.RecentChatAdapter
import com.difft.android.chat.recent.RoomViewData
import com.difft.android.chat.search.SearchChatHistoryAdapter
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchMessageActivity
import com.difft.android.chat.search.SearchUtils
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.databinding.ActivitySearchBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.subjects.BehaviorSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class SearchActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, SearchActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivitySearchBinding by viewbind()
    private var key: String = ""

    @Inject
    lateinit var inviteUtils: InviteUtils

    private val searchSubject = BehaviorSubject.createDefault("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.edittextSearchInput.addTextChangedListener {
            searchSubject.onNext(it.toString())
        }

        searchSubject
            .debounce(300, TimeUnit.MILLISECONDS)//防止频繁触发搜索
            .distinctUntilChanged()
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                key = it.toString().trim()

                mRecentAdapter.setOrUpdateSearchKey(key)
                mContactsAdapter.setOrUpdateSearchKey(key)
                mGroupsAdapter.setOrUpdateSearchKey(key)

                resetButtonClear()
                if (key.isNotEmpty()) {
                    searchWithCoroutines()
                } else {
                    showNoResults(getString(R.string.search_messages_default_tips))
                }
            }, { L.w { "[SearchActivity] initView searchSubject error: ${it.stackTraceToString()}" } })


        mBinding.buttonClear.setOnClickListener {
            mBinding.edittextSearchInput.text = null
        }

        mBinding.recyclerviewRecentChats.apply {
            this.adapter = mRecentAdapter
            this.layoutManager = LinearLayoutManager(this@SearchActivity)
            itemAnimator = null
        }

        mBinding.recyclerviewContacts.apply {
            this.adapter = mContactsAdapter
            this.layoutManager = LinearLayoutManager(this@SearchActivity)
            itemAnimator = null
        }

        mBinding.recyclerviewGroup.apply {
            this.adapter = mGroupsAdapter
            this.layoutManager = LinearLayoutManager(this@SearchActivity)
            itemAnimator = null
        }

        mBinding.recyclerviewChatHistory.apply {
            this.adapter = mSearchChatHistoryAdapter
            this.layoutManager = LinearLayoutManager(this@SearchActivity)
            itemAnimator = null
        }
    }

    private fun resetButtonClear() {
        mBinding.buttonClear.animate().apply {
            cancel()
            val toAlpha = if (key.isNotEmpty()) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    private fun showNoResults(tipContent: String) {
        mRecentAdapter.submitList(emptyList())
        mBinding.llRecentChats.visibility = View.GONE
        mContactsAdapter.submitList(emptyList())
        mBinding.llContacts.visibility = View.GONE
        mGroupsAdapter.submitList(emptyList())
        mBinding.llGroups.visibility = View.GONE
        mSearchChatHistoryAdapter.submitList(emptyList())
        mBinding.llChatHistory.visibility = View.GONE

        mBinding.tvTips.visibility = View.VISIBLE
        mBinding.tvTips.text = tipContent
        mBinding.vContent.visibility = View.GONE
    }

    private fun searchWithCoroutines() {
        // 为当前搜索生成唯一标识
        currentSearchId = System.currentTimeMillis()

        // 初始化搜索结果状态
        var recentChats = emptyList<RoomViewData>()
        var contacts = emptyList<ContactorModel>()
        var groups = emptyList<GroupUIData>()
        var chatHistory = emptyList<SearchChatHistoryViewData>()

        lifecycleScope.launch {
            try {
                // 并发执行所有搜索，每个搜索完成后立即更新UI
                val recentChatsDeferred = async {
                    try {
                        val result = searchRecentChats()
                        L.d { "[search] recent chats completed: ${result.size}" }
                        recentChats = result
                        updateUIWithSearchId(currentSearchId, SearchResult(recentChats, contacts, groups, chatHistory))
                        result
                    } catch (e: Exception) {
                        L.w { "[search] search recent chats error: ${e.stackTraceToString()}" }
                        recentChats = emptyList()
                        updateUIWithSearchId(currentSearchId, SearchResult(recentChats, contacts, groups, chatHistory))
                        emptyList()
                    }
                }

                val contactsDeferred = async {
                    try {
                        val result = searchContacts()
                        L.d { "[search] contacts completed: ${result.size}" }
                        contacts = result
                        updateUIWithSearchId(currentSearchId, SearchResult(recentChats, contacts, groups, chatHistory))
                        result
                    } catch (e: Exception) {
                        L.w { "[search] search contacts error: ${e.stackTraceToString()}" }
                        contacts = emptyList()
                        updateUIWithSearchId(currentSearchId, SearchResult(recentChats, contacts, groups, chatHistory))
                        emptyList()
                    }
                }

                val groupsDeferred = async {
                    try {
                        val result = searchGroups()
                        L.d { "[search] groups completed: ${result.size}" }
                        groups = result
                        updateUIWithSearchId(currentSearchId, SearchResult(recentChats, contacts, groups, chatHistory))
                        result
                    } catch (e: Exception) {
                        L.w { "[search] search groups error: ${e.stackTraceToString()}" }
                        groups = emptyList()
                        updateUIWithSearchId(currentSearchId, SearchResult(recentChats, contacts, groups, chatHistory))
                        emptyList()
                    }
                }

                val chatHistoryDeferred = async {
                    try {
                        val result = searchChatHistory()
                        L.d { "[search] chat history completed: ${result.size}" }
                        chatHistory = result
                        updateUIWithSearchId(currentSearchId, SearchResult(recentChats, contacts, groups, chatHistory))
                        result
                    } catch (e: Exception) {
                        L.w { "[search] search messages error: ${e.stackTraceToString()}" }
                        chatHistory = emptyList()
                        updateUIWithSearchId(currentSearchId, SearchResult(recentChats, contacts, groups, chatHistory))
                        emptyList()
                    }
                }

                awaitAll(recentChatsDeferred, contactsDeferred, groupsDeferred, chatHistoryDeferred)

                L.d { "[search] all searches completed" }

            } catch (e: Exception) {
                L.w { "[search] search error: ${e.stackTraceToString()}" }
                showNoResults(getString(R.string.search_no_results_found))
            }
        }
    }

    private suspend fun searchRecentChats(): List<RoomViewData> = withContext(Dispatchers.IO) {
        try {
            val commonQuery = DBRoomModel.roomId.notEq("server")
                .and(DBRoomModel.roomName.notNull())
                .and(DBRoomModel.roomName.notEq(""))

            val baseRooms = if (key.isEmpty()) {
                wcdb.room.getAllObjects(commonQuery)
            } else {
                wcdb.room.getAllObjects(
                    commonQuery.and(DBRoomModel.roomName.upper().like("%${key.uppercase()}%"))
                )
            }

            // 如果需要添加收藏房间且结果中不包含，则添加
            val rooms = baseRooms + if (getString(com.difft.android.base.R.string.chat_favorites).contains(key, true) &&
                !baseRooms.any { it.roomId == globalServices.myId }
            ) {
                wcdb.room.getFirstObject(DBRoomModel.roomId.eq(globalServices.myId))?.let { listOf(it) } ?: emptyList()
            } else {
                emptyList()
            }

            val sortedRooms = rooms.sortedWith(compareByDescending<RoomModel> { it.pinnedTime ?: 0L }
                .thenByDescending { it.lastActiveTime })

            // 转换为 RoomViewData
            sortedRooms.map { room ->
                RoomViewData(
                    roomId = room.roomId,
                    type = if (room.roomType == 1) RoomViewData.Type.Group else RoomViewData.Type.OneOnOne,
                    roomName = room.roomName,
                    roomAvatarJson = room.roomAvatarJson,
                    lastDisplayContent = room.lastDisplayContent,
                    lastActiveTime = room.lastActiveTime,
                    unreadMessageNum = room.unreadMessageNum,
                    muteStatus = room.muteStatus,
                    pinnedTime = room.pinnedTime,
                    mentionType = room.mentionType,
                    criticalAlertType = room.criticalAlertType,
                    messageExpiry = room.messageExpiry,
                    groupMembersNumber = room.groupMembersNumber
                )
            }
        } catch (e: Exception) {
            L.w { "[search] search recent chats error: ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private suspend fun searchContacts(): List<ContactorModel> = withContext(Dispatchers.IO) {
        try {
            wcdb.contactor.search(key)
        } catch (e: Exception) {
            L.w { "[search] search contacts error: ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private suspend fun searchGroups(): List<GroupUIData> = withContext(Dispatchers.IO) {
        try {
            wcdb.group.searchByNameAndGroupMembers(key)
                .map { GroupUtil.convert(it) }
        } catch (e: Exception) {
            L.w { "[search] search groups error: ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private suspend fun searchChatHistory(): List<SearchChatHistoryViewData> = withContext(Dispatchers.IO) {
        try {
            val result = wcdb.message.getAllObjects(
                DBMessageModel.messageText.upper().like("%${key.uppercase()}%")
                    .and(DBMessageModel.type.notEq(2))
                    .and(DBMessageModel.mode.notEq(1)) // Exclude confidential messages
            ).groupBy { wcdb.room.getFirstObject(DBRoomModel.roomId.eq(it.roomId)) }

            val topFourEntries = if (result.size > 4) {
                result.entries.take(5).associate { it.key to it.value }
            } else {
                result
            }

            SearchUtils.createSearchChatHistoryViewDataList(
                this@SearchActivity,
                topFourEntries.filter { it.key != null }.map { it.key!! to it.value }.toMap()
            ).await()
        } catch (e: Exception) {
            L.w { "[search] search messages error: ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private var currentSearchId: Long = 0L

    private fun updateUIWithSearchId(searchId: Long, result: SearchResult) {
        // 检查是否是当前搜索的结果，如果不是则忽略
        if (searchId != currentSearchId) {
            L.d { "[search] ignoring outdated search result, current: $currentSearchId, received: $searchId" }
            return
        }

        L.d { "[search] updateUI for searchId: $searchId" }
        mBinding.vContent.visibility = View.VISIBLE
        mBinding.tvTips.visibility = View.GONE

        // 更新最近聊天
        if (result.recentChats.isEmpty()) {
            mBinding.llRecentChats.visibility = View.GONE
        } else {
            mBinding.llRecentChats.visibility = View.VISIBLE
            mRecentAdapter.submitList(result.recentChats.map { ListItem.ChatItem(it) })
        }

        // 更新联系人
        if (result.contacts.isEmpty()) {
            mBinding.llContacts.visibility = View.GONE
        } else {
            mBinding.llContacts.visibility = View.VISIBLE
            if (result.contacts.size > 4) {
                mContactsAdapter.submitList(result.contacts.subList(0, 4))
                mBinding.clSearchContactsMore.root.visibility = View.VISIBLE
                mBinding.clSearchContactsMore.root.setOnClickListener {
                    SearchMoreActivity.startActivity(this, SearchMoreActivity.SEARCH_TYPE_CONTACT, key)
                }
            } else {
                mContactsAdapter.submitList(result.contacts)
                mBinding.clSearchContactsMore.root.visibility = View.GONE
            }
        }

        // 更新群组
        if (result.groups.isEmpty()) {
            mBinding.llGroups.visibility = View.GONE
        } else {
            mBinding.llGroups.visibility = View.VISIBLE
            if (result.groups.size > 4) {
                mGroupsAdapter.submitList(result.groups.subList(0, 4))
                mBinding.clSearchGroupsMore.root.visibility = View.VISIBLE
                mBinding.clSearchGroupsMore.root.setOnClickListener {
                    SearchMoreActivity.startActivity(this, SearchMoreActivity.SEARCH_TYPE_GROUP, key)
                }
            } else {
                mGroupsAdapter.submitList(result.groups)
                mBinding.clSearchGroupsMore.root.visibility = View.GONE
            }
        }

        // 更新聊天历史
        if (result.chatHistory.isEmpty()) {
            mBinding.llChatHistory.visibility = View.GONE
        } else {
            mBinding.llChatHistory.visibility = View.VISIBLE
            if (result.chatHistory.size > 4) {
                mSearchChatHistoryAdapter.submitList(result.chatHistory.subList(0, 4))
                mBinding.clSearchChatHistoryMore.root.visibility = View.VISIBLE
                mBinding.clSearchChatHistoryMore.root.setOnClickListener {
                    SearchMoreActivity.startActivity(this, SearchMoreActivity.SEARCH_TYPE_MESSAGE, key)
                }
            } else {
                mSearchChatHistoryAdapter.submitList(result.chatHistory)
                mBinding.clSearchChatHistoryMore.root.visibility = View.GONE
            }
        }

        // 检查是否所有结果都为空
        if (result.recentChats.isEmpty() && result.contacts.isEmpty() &&
            result.groups.isEmpty() && result.chatHistory.isEmpty()
        ) {
            showNoResults(getString(R.string.search_no_results_found))
        }
    }

    private val mRecentAdapter: RecentChatAdapter by lazy {
        object : RecentChatAdapter(this@SearchActivity, isForSearch = true) {
            override fun onItemClicked(roomViewData: RoomViewData, position: Int) {
                when (roomViewData.type) {
                    is RoomViewData.Type.OneOnOne -> {
                        ChatActivity.startActivity(this@SearchActivity, roomViewData.roomId)
                    }

                    is RoomViewData.Type.Group -> {
                        GroupChatContentActivity.startActivity(this@SearchActivity, roomViewData.roomId)
                    }
                }
            }

            override fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int) {}
        }
    }

    private val mContactsAdapter: SearchContactAdapter by lazy {
        object : SearchContactAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                ChatActivity.startActivity(this@SearchActivity, contact.id)
            }
        }
    }

    private val mGroupsAdapter: SearchGroupsAdapter by lazy {
        object : SearchGroupsAdapter() {
            override fun onItemClick(group: GroupUIData) {
                GroupChatContentActivity.startActivity(this@SearchActivity, group.gid)
            }
        }
    }

    private val mSearchChatHistoryAdapter: SearchChatHistoryAdapter by lazy {
        object : SearchChatHistoryAdapter() {
            override fun onItemClicked(data: SearchChatHistoryViewData, position: Int) {
                if (data.onlyOneResult) {
                    if (data.type == SearchChatHistoryViewData.Type.Group) {
                        GroupChatContentActivity.startActivity(this@SearchActivity, data.conversationId, jumpMessageTimeStamp = data.messageTimeStamp)
                    } else {
                        ChatActivity.startActivity(this@SearchActivity, data.conversationId, jumpMessageTimeStamp = data.messageTimeStamp)
                    }
                } else {
                    val etContent = mBinding.edittextSearchInput.text.toString().trim()
                    SearchMessageActivity.startActivity(this@SearchActivity, data.conversationId, data.type == SearchChatHistoryViewData.Type.Group, etContent)
                }
            }
        }
    }
}

data class SearchResult(
    val recentChats: List<RoomViewData>,
    val contacts: List<ContactorModel>,
    val groups: List<GroupUIData>,
    val chatHistory: List<SearchChatHistoryViewData>
)