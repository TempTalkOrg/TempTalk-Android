package com.difft.android.search

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.hideDetailKeyboardBeforeLeaving
import com.difft.android.base.utils.pane.UncoverableSearchPage
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.recent.ListItem
import com.difft.android.chat.recent.RecentChatAdapter
import com.difft.android.chat.recent.RoomViewData
import com.difft.android.chat.search.SearchChatHistoryAdapter
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchMessageDestination
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.databinding.ActivitySearchBinding
import com.difft.android.search.mvi.GlobalSearchContract
import com.difft.android.search.mvi.GlobalSearchViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.difft.app.database.models.ContactorModel
import kotlin.math.abs
import com.difft.android.chat.R as ChatR

/**
 * The global search page: a page of the home screen's search layer or of its shell
 * [SearchActivity]. Results open through the conversation router (hosted: the current tab's
 * stack; shell: a full-window conversation), a multi-hit row and "More" push pages through
 * [detailNavigation] (hosted: onto the layer; shell: their Activities), and the back arrow pops
 * the page (hosted: closes the layer; shell: finishes). Query and results live in the ViewModel.
 */
@AndroidEntryPoint
class GlobalSearchFragment : Fragment(), SearchSelectionPage, UncoverableSearchPage {

    private var _binding: ActivitySearchBinding? = null
    private val binding: ActivitySearchBinding get() = _binding!!

    private val viewModel: GlobalSearchViewModel by viewModels()

    private val recentAdapter: RecentChatAdapter by lazy {
        object : RecentChatAdapter(requireActivity(), isForSearch = true) {
            override fun onItemClicked(roomViewData: RoomViewData, position: Int) {
                when (roomViewData.type) {
                    is RoomViewData.Type.OneOnOne -> openOneOnOne(roomViewData.roomId)
                    is RoomViewData.Type.Group -> openGroup(roomViewData.roomId)
                }
            }

            override fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int) = Unit
        }
    }

    private val contactsAdapter: SearchContactAdapter by lazy {
        object : SearchContactAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) = openOneOnOne(contact.id)
        }
    }

    private val groupsAdapter: SearchGroupsAdapter by lazy {
        object : SearchGroupsAdapter() {
            override fun onItemClick(group: GroupUIData) = openGroup(group.gid)
        }
    }

    private val historyAdapter: SearchChatHistoryAdapter by lazy {
        object : SearchChatHistoryAdapter() {
            override fun onItemClicked(data: SearchChatHistoryViewData, position: Int) {
                val isGroup = data.type == SearchChatHistoryViewData.Type.Group
                if (data.onlyOneResult) {
                    if (isGroup) openGroup(data.conversationId, data.messageTimeStamp) else openOneOnOne(data.conversationId, data.messageTimeStamp)
                } else {
                    detailNavigation().push(this@GlobalSearchFragment, SearchMessageDestination(data.conversationId, isGroup, key = binding.searchInput.query.trim()))
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivitySearchBinding.inflate(inflater, container, false)
        // Hosted, the keyboard opens through detailKeyboard (fresh open only), not on every composition.
        if (isInDetailStack()) binding.searchInput.autoFocus = false
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(binding.root, ime = true)
        detailKeyboard(showOnFirstOpen = true, focus = { binding.searchInput.focusAndShowKeyboard() }, savedInstanceState = savedInstanceState)

        binding.searchInput.onQueryChanged = { viewModel.dispatch(GlobalSearchContract.Intent.QueryChanged(it)) }
        binding.searchInput.onClear = { viewModel.dispatch(GlobalSearchContract.Intent.Clear) }

        binding.vContent.hideKeyboardOnDrag()
        binding.recyclerviewRecentChats.setUp(recentAdapter)
        binding.recyclerviewContacts.setUp(contactsAdapter)
        binding.recyclerviewGroup.setUp(groupsAdapter)
        binding.recyclerviewChatHistory.setUp(historyAdapter)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun RecyclerView.setUp(adapter: RecyclerView.Adapter<*>) {
        this.adapter = adapter
        layoutManager = LinearLayoutManager(requireContext())
        itemAnimator = null
        addOnScrollListener(dragHidesKeyboard)
    }

    /**
     * Dragging the results hides the keyboard (D2). Only a touch-driven drag: a results list that
     * shrinks under a narrower query clamps the scroll position programmatically, and that must
     * not close the keyboard mid-typing.
     */
    private val dragHidesKeyboard = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) _binding?.searchInput?.hideKeyboard()
        }
    }

    /**
     * D2 for a drag that starts outside the lists (a section heading, the gap under the last
     * row): the outer scroll container sees those touches itself. Observes only and never
     * consumes, so row clicks and the container's own scrolling are untouched.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun NestedScrollView.hideKeyboardOnDrag() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downY = 0f
        var hidden = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    hidden = false
                }
                MotionEvent.ACTION_MOVE -> if (!hidden && abs(event.y - downY) > slop) {
                    hidden = true
                    _binding?.searchInput?.hideKeyboard()
                }
            }
            false
        }
    }

    private var renderedKey: String? = null

    private fun render(state: GlobalSearchContract.State) {
        val key = state.query
        // The adapters rebind everything on a key change; the state emits several times per query.
        if (key != renderedKey) {
            renderedKey = key
            recentAdapter.setOrUpdateSearchKey(key)
            contactsAdapter.setOrUpdateSearchKey(key)
            groupsAdapter.setOrUpdateSearchKey(key)
        }

        binding.vContent.visibility = if (state.tips == GlobalSearchContract.Tips.NONE) View.VISIBLE else View.GONE
        binding.tvTips.visibility = if (state.tips == GlobalSearchContract.Tips.NONE) View.GONE else View.VISIBLE
        binding.tvTips.text = when (state.tips) {
            GlobalSearchContract.Tips.DEFAULT -> getString(ChatR.string.search_messages_default_tips)
            GlobalSearchContract.Tips.NO_RESULTS -> getString(ChatR.string.search_no_results_found)
            GlobalSearchContract.Tips.NONE -> ""
        }

        binding.llRecentChats.visibility = if (state.recent.isEmpty()) View.GONE else View.VISIBLE
        recentAdapter.submitList(state.recent.map { ListItem.ChatItem(it) })

        renderSection(binding.llContacts, binding.clSearchContactsMore.root, state.contacts, SearchMoreActivity.SEARCH_TYPE_CONTACT, key) {
            contactsAdapter.submitList(it)
        }
        renderSection(binding.llGroups, binding.clSearchGroupsMore.root, state.groups, SearchMoreActivity.SEARCH_TYPE_GROUP, key) {
            groupsAdapter.submitList(it)
        }
        renderSection(binding.llChatHistory, binding.clSearchChatHistoryMore.root, state.history, SearchMoreActivity.SEARCH_TYPE_MESSAGE, key) {
            historyAdapter.updateWithSearchKey(key, it)
        }
    }

    /** A section shows its first [MORE_THRESHOLD] rows and a "More" row that pushes the full list. */
    private fun <T> renderSection(section: View, more: View, items: List<T>, type: Int, key: String, submit: (List<T>) -> Unit) {
        section.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.size > MORE_THRESHOLD) {
            submit(items.subList(0, MORE_THRESHOLD))
            more.visibility = View.VISIBLE
            more.setOnClickListener { detailNavigation().push(this, SearchMoreDestination(type, key)) }
        } else {
            submit(items)
            more.visibility = View.GONE
        }
    }

    /** One-pane: the conversation layer over this page popped; the field takes the keyboard back. */
    override fun onUncovered() {
        _binding?.searchInput?.focusAndShowKeyboard()
    }

    /** One-pane: a conversation layer covered this page (not through a result tap); the field lets the keyboard go. */
    override fun onCovered() {
        _binding?.searchInput?.hideKeyboard()
    }

    /** Two-pane highlight of the open conversation or contact in every result list (view may not exist yet). */
    override fun showSelection(selectedId: String?) {
        recentAdapter.selectedId = selectedId
        contactsAdapter.selectedId = selectedId
        groupsAdapter.selectedId = selectedId
        historyAdapter.selectedId = selectedId
    }

    private fun openOneOnOne(contactId: String, jumpTimestamp: Long? = null) {
        // Hosted, the conversation lays out in this window: let the keyboard start closing first.
        hideDetailKeyboardBeforeLeaving()
        ChatActivity.startActivity(requireActivity(), contactId, jumpMessageTimeStamp = jumpTimestamp)
    }

    private fun openGroup(groupId: String, jumpTimestamp: Long? = null) {
        hideDetailKeyboardBeforeLeaving()
        GroupChatContentActivity.startActivity(requireActivity(), groupId, jumpMessageTimeStamp = jumpTimestamp)
    }

    companion object {
        private const val MORE_THRESHOLD = 4

        fun newInstance(): GlobalSearchFragment = GlobalSearchFragment()
    }
}
