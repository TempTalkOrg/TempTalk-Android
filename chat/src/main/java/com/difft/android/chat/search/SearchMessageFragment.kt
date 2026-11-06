package com.difft.android.chat.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.UncoverableSearchPage
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.hideDetailKeyboardBeforeLeaving
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivitySearchMessageBinding
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

/**
 * Search in one conversation's messages. A page of the home detail stack or of its shell
 * [SearchMessageActivity]. A result opens the conversation at that message: hosted, the tab's
 * root is replaced by a new instance carrying the jump (same conversation included); in a shell,
 * through the conversation router as before. The query is view state and searches again from
 * the first page after a fold; later pages are not restored.
 */
@AndroidEntryPoint
class SearchMessageFragment : Fragment(), UncoverableSearchPage {

    @Inject
    lateinit var wcdb: WCDB

    private var _binding: ActivitySearchMessageBinding? = null
    private val binding: ActivitySearchMessageBinding get() = _binding!!

    private val conversationId: String by lazy { arguments?.getString(ARG_CONVERSATION_ID).orEmpty() }
    private val isGroup: Boolean by lazy { arguments?.getBoolean(ARG_IS_GROUP, false) ?: false }

    private var key: String = ""

    private var paginationCursor: Long? = null
    private var hasMoreMessages = false
    private var isLoadingMore = false
    private val accumulatedMessageData = mutableListOf<SearchChatHistoryViewData>()

    private var searchJob: Job? = null

    private val resultsAdapter: SearchChatHistoryAdapter by lazy {
        object : SearchChatHistoryAdapter(isForMessageSearch = true) {
            override fun onItemClicked(data: SearchChatHistoryViewData, position: Int) {
                // The conversation lays out in this window: let the keyboard start closing first.
                hideDetailKeyboardBeforeLeaving()
                val kind = if (isGroup) ConversationTarget.Kind.GROUP else ConversationTarget.Kind.ONE_ON_ONE
                detailNavigation().openConversation(ConversationTarget(kind, conversationId, jumpTimestamp = data.messageTimeStamp))
            }
        }
    }

    private val paginationScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val lastVisible = layoutManager.findLastVisibleItemPosition()
            if (!isLoadingMore && hasMoreMessages && lastVisible >= layoutManager.itemCount - LOAD_MORE_THRESHOLD) {
                loadPage(isInitialLoad = false)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivitySearchMessageBinding.inflate(inflater, container, false)
        // Hosted, the keyboard opens through detailKeyboard (fresh open only), not on every composition.
        if (isInDetailStack()) binding.searchInput.autoFocus = false
        return binding.root
    }

    override fun onDestroyView() {
        searchJob = null
        _binding = null
        super.onDestroyView()
    }

    /** One-pane search layer: the conversation layer over this page popped; the field takes the keyboard back. */
    override fun onUncovered() {
        _binding?.searchInput?.focusAndShowKeyboard()
    }

    /** One-pane search layer: a conversation layer covered this page without a result tap; the keyboard goes. */
    override fun onCovered() {
        _binding?.searchInput?.hideKeyboard()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (conversationId.isEmpty()) {
            L.w { "[SearchMessage] page without a conversation" }
            detailNavigation().pop(this)
            return
        }
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(binding.root, ime = true)
        detailKeyboard(showOnFirstOpen = true, focus = { binding.searchInput.focusAndShowKeyboard() }, savedInstanceState = savedInstanceState)

        binding.searchInput.onQueryChanged = { raw -> scheduleSearch(raw.trim()) }
        binding.searchInput.onClear = { scheduleSearch("") }

        binding.recyclerviewChatHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
            adapter = resultsAdapter
            addOnScrollListener(paginationScrollListener)
        }

        // After a fold the restored query re-fires onQueryChanged and searches again.
        if (savedInstanceState == null) {
            key = arguments?.getString(ARG_KEY).orEmpty()
            binding.searchInput.query = key
            if (key.isNotEmpty()) loadPage(isInitialLoad = true)
        }
    }

    private fun loadPage(isInitialLoad: Boolean) {
        if (isLoadingMore && !isInitialLoad) return
        isLoadingMore = true
        val keyword = key
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (result, viewData) = withContext(Dispatchers.IO) {
                    val result = wcdb.loadPaginatedMessagesByKeyword(
                        keyword = keyword,
                        limit = PAGE_SIZE,
                        cursorTimestamp = if (isInitialLoad) null else paginationCursor,
                        conversationId = conversationId
                    )
                    result to SearchUtils.createSearchChatHistoryViewDataList(result.messages)
                }
                paginationCursor = result.lastTimestamp
                hasMoreMessages = result.hasMore
                if (isInitialLoad) accumulatedMessageData.clear()
                accumulatedMessageData.addAll(viewData)
                val list = accumulatedMessageData.toList()
                if (list.isEmpty()) {
                    showNoResults(getString(R.string.search_no_results_found))
                } else {
                    binding.recyclerviewChatHistory.visibility = View.VISIBLE
                    binding.llTips.visibility = View.GONE
                    resultsAdapter.updateWithSearchKey(keyword, list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isInitialLoad) showNoResults(getString(R.string.search_no_results_found))
                L.w { "[SearchMessage] loadPage error: ${e.stackTraceToString()}" }
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun scheduleSearch(text: String) {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            if (text == key) return@launch
            key = text
            if (key.isNotEmpty()) loadPage(isInitialLoad = true) else showNoResults(getString(R.string.search_messages_default_tips))
        }
    }

    private fun showNoResults(tipContent: String) {
        val binding = _binding ?: return
        binding.recyclerviewChatHistory.visibility = View.GONE
        resultsAdapter.updateWithSearchKey("", emptyList())
        binding.llTips.visibility = View.VISIBLE
        binding.tvTips.text = tipContent
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val LOAD_MORE_THRESHOLD = 5
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val ARG_CONVERSATION_ID = "conversationId"
        private const val ARG_IS_GROUP = "isGroup"
        private const val ARG_KEY = "key"

        fun arguments(conversationId: String, isGroup: Boolean, key: String?): Bundle = Bundle().apply {
            putString(ARG_CONVERSATION_ID, conversationId)
            putBoolean(ARG_IS_GROUP, isGroup)
            putString(ARG_KEY, key)
        }

        fun newInstance(arguments: Bundle?): SearchMessageFragment = SearchMessageFragment().apply { this.arguments = arguments }
    }
}

/** Message search in one conversation as a detail destination (home stack, or its shell Activity). */
class SearchMessageDestination(
    private val conversationId: String,
    private val isGroup: Boolean,
    private val key: String? = null,
) : DetailDestination {
    override fun newFragment(): Fragment = SearchMessageFragment.newInstance(SearchMessageFragment.arguments(conversationId, isGroup, key))
    override fun shellIntent(context: Context): Intent = SearchMessageActivity.intent(context, conversationId, isGroup, key)
    override val subjectId: String get() = conversationId
}
