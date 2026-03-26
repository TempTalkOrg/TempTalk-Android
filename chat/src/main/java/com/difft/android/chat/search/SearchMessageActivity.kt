package com.difft.android.chat.search

import android.annotation.SuppressLint
import com.difft.android.base.log.lumberjack.L
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.BaseActivity
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivitySearchMessageBinding
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.ui.ChatActivity
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.loadPaginatedMessagesByKeyword
import javax.inject.Inject

@AndroidEntryPoint
class SearchMessageActivity : BaseActivity() {

    companion object {
        private const val PAGE_SIZE = 20
        private const val LOAD_MORE_THRESHOLD = 5

        fun startActivity(activity: Activity, conversationId: String, isGroup: Boolean, key: String?) {
            val intent = Intent(activity, SearchMessageActivity::class.java)
            intent.putExtra("conversationId", conversationId)
            intent.putExtra("isGroup", isGroup)
            intent.putExtra("key", key)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivitySearchMessageBinding by viewbind()

    @Inject
    lateinit var wcdb: WCDB

    private val conversationId: String by lazy {
        intent.getStringExtra("conversationId") ?: ""
    }
    private val isGroup: Boolean by lazy {
        intent.getBooleanExtra("isGroup", false)
    }

    private var key: String = ""

    // Pagination state
    private var paginationCursor: Long? = null
    private var hasMoreMessages = false
    private var isLoadingMore = false
    private val accumulatedMessageData = mutableListOf<SearchChatHistoryViewData>()

    private var searchJob: Job? = null

    private val mSearchChatHistoryAdapter: SearchChatHistoryAdapter by lazy {
        object : SearchChatHistoryAdapter(isForMessageSearch = true) {
            override fun onItemClicked(data: SearchChatHistoryViewData, position: Int) {
                if (isGroup) {
                    GroupChatContentActivity.startActivity(this@SearchMessageActivity, conversationId, jumpMessageTimeStamp = data.messageTimeStamp)
                } else {
                    ChatActivity.startActivity(this@SearchMessageActivity, conversationId, jumpMessageTimeStamp = data.messageTimeStamp)
                }
            }
        }
    }

    private val paginationScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val lastVisible = layoutManager.findLastVisibleItemPosition()
            val total = layoutManager.itemCount
            if (!isLoadingMore && hasMoreMessages && lastVisible >= total - LOAD_MORE_THRESHOLD) {
                loadPage(isInitialLoad = false)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        key = intent.getStringExtra("key") ?: ""

        initView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.edittextSearchInput.addTextChangedListener {
            val text = it.toString().trim()
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(300)
                if (text == key) return@launch
                key = text
                resetButtonClear()
                if (key.isNotEmpty()) {
                    loadPage(isInitialLoad = true)
                } else {
                    showNoResults(getString(R.string.search_messages_default_tips))
                }
            }
        }

        mBinding.recyclerviewChatHistory.apply {
            layoutManager = LinearLayoutManager(this@SearchMessageActivity)
            itemAnimator = null
            adapter = mSearchChatHistoryAdapter
            addOnScrollListener(paginationScrollListener)
        }

        mBinding.buttonClear.setOnClickListener {
            mBinding.edittextSearchInput.text = null
        }

        mBinding.edittextSearchInput.setText(key)
        mBinding.edittextSearchInput.setSelection(key.length)

        if (key.isNotEmpty()) {
            loadPage(isInitialLoad = true)
        }
    }

    private fun loadPage(isInitialLoad: Boolean) {
        if (isLoadingMore && !isInitialLoad) return
        isLoadingMore = true

        lifecycleScope.launch {
            try {
                val (result, viewData) = withContext(Dispatchers.IO) {
                    val result = wcdb.loadPaginatedMessagesByKeyword(
                        keyword = key,
                        limit = PAGE_SIZE,
                        cursorTimestamp = if (isInitialLoad) null else paginationCursor,
                        conversationId = conversationId
                    )
                    val viewData = SearchUtils.createSearchChatHistoryViewDataList(result.messages)
                    result to viewData
                }
                paginationCursor = result.lastTimestamp
                hasMoreMessages = result.hasMore
                if (isInitialLoad) accumulatedMessageData.clear()
                accumulatedMessageData.addAll(viewData)
                val list = accumulatedMessageData.toList()
                if (list.isEmpty()) {
                    showNoResults(getString(R.string.search_no_results_found))
                } else {
                    mBinding.recyclerviewChatHistory.visibility = View.VISIBLE
                    mBinding.tvTips.visibility = View.GONE
                    mSearchChatHistoryAdapter.updateWithSearchKey(key, list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isInitialLoad) showNoResults(getString(R.string.search_no_results_found))
                L.w { "[SearchMessageActivity] loadPage error: ${e.stackTraceToString()}" }
            } finally {
                isLoadingMore = false
            }
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
        mBinding.recyclerviewChatHistory.visibility = View.GONE
        mSearchChatHistoryAdapter.updateWithSearchKey("", emptyList())
        mBinding.tvTips.visibility = View.VISIBLE
        mBinding.tvTips.text = tipContent
    }
}
