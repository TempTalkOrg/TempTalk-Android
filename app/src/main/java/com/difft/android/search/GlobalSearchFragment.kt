package com.difft.android.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.hideDetailKeyboardBeforeLeaving
import com.difft.android.base.utils.pane.UncoverableSearchPage
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchMessageDestination
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.base.widget.applyFloatingSurface
import com.difft.android.databinding.ActivitySearchBinding
import com.difft.android.dualpane.ListPaneLayer
import com.difft.android.search.mvi.GlobalSearchContract
import com.difft.android.search.mvi.GlobalSearchViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.difft.app.database.models.ContactorModel
import kotlin.math.abs










@AndroidEntryPoint
class GlobalSearchFragment : Fragment(), SearchSelectionPage, UncoverableSearchPage {

    private var _binding: ActivitySearchBinding? = null
    private val binding: ActivitySearchBinding get() = _binding!!

    private val viewModel: GlobalSearchViewModel by viewModels()

    private val resultsAdapter: GlobalSearchResultsAdapter by lazy {
        GlobalSearchResultsAdapter(object : GlobalSearchResultsAdapter.Listener {
            override fun onScopeSelected(scope: GlobalSearchContract.Scope) = viewModel.dispatch(GlobalSearchContract.Intent.SelectScope(scope))
            override fun onContactClicked(contact: ContactorModel) = openOneOnOne(contact.id)
            override fun onGroupClicked(group: GroupUIData) = openGroup(group.gid)
            override fun onHistoryClicked(history: SearchChatHistoryViewData) = openHistory(history)
            override fun onViewAllClicked(section: GlobalSearchContract.Section) =
                viewModel.dispatch(GlobalSearchContract.Intent.SelectScope(section.scope))

            override fun onMoreClicked(section: GlobalSearchContract.Section) {
                val type = if (section == GlobalSearchContract.Section.GROUPS) SearchMoreActivity.SEARCH_TYPE_GROUP else SearchMoreActivity.SEARCH_TYPE_CONTACT
                detailNavigation().push(this@GlobalSearchFragment, SearchMoreDestination(type, viewModel.state.value.query))
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivitySearchBinding.inflate(inflater, container, false)
        
        if (isInDetailStack()) binding.searchInput.autoFocus = false
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpSearchBar()
        applyDetailPageInsets(binding.root, ime = true)
        
        val focus = { _binding?.searchInput?.focusAndShowKeyboard() ?: Unit }
        val focusAfterReveal = { (activity as? ListPaneLayer.Owner)?.listLayer?.runAfterReveal(focus) ?: focus() }
        detailKeyboard(showOnFirstOpen = true, focus = focusAfterReveal, savedInstanceState = savedInstanceState)

        binding.searchInput.onQueryChanged = { viewModel.dispatch(GlobalSearchContract.Intent.QueryChanged(it)) }
        binding.searchInput.onClear = { viewModel.dispatch(GlobalSearchContract.Intent.Clear) }

        setUpResults()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    




    private fun setUpSearchBar() {
        val density = resources.displayMetrics.density
        binding.searchBar.applyFloatingSurface(cornerRadiusPx = resources.getDimension(com.difft.android.base.R.dimen.radius_floating))
        binding.ibBack.applyFloatingSurface(maxCircleDiameterDp = CLOSE_SIZE_DP, pressable = true)
        binding.ibBack.visibility = if (showsDetailBackButton()) View.VISIBLE else View.GONE
        binding.ibBack.setOnClickListener { detailNavigation().pop(this) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.searchBarRow) { row, insets ->
            val margin = SearchBarPlacement.bottomMarginPx(
                keyboardUp = insets.isVisible(WindowInsetsCompat.Type.ime()),
                navBottomPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                density = density,
            )
            if ((row.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin != margin) {
                row.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = margin }
            }
            insets
        }
        binding.searchBarRow.addOnLayoutChangeListener { row, _, top, _, _, _, _, _, _ ->
            val current = _binding ?: return@addOnLayoutChangeListener
            val padding = SearchBarPlacement.listBottomPaddingPx(current.results.bottom, top, density)
            if (current.vContent.paddingBottom != padding) row.post { _binding?.vContent?.updatePadding(bottom = padding) }
        }
    }

    private fun setUpResults() {
        val list = binding.vContent
        list.adapter = resultsAdapter
        list.layoutManager = LinearLayoutManager(requireContext())
        list.itemAnimator = null
        list.addItemDecoration(SearchResultDividerDecoration(requireContext()))
        list.addOnScrollListener(dragHidesKeyboard)
        list.addOnItemTouchListener(dragOutsideRowsHidesKeyboard())
        
        list.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val rv = view as RecyclerView
            val pills = rv.findViewHolderForAdapterPosition(0)?.itemView?.height ?: 0
            resultsAdapter.emptyMinHeightPx.intValue = (rv.height - rv.paddingTop - rv.paddingBottom - pills).coerceAtLeast(0)
        }
    }

    




    private val dragHidesKeyboard = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) _binding?.searchInput?.hideKeyboard()
        }
    }

    




    private fun dragOutsideRowsHidesKeyboard() = object : RecyclerView.SimpleOnItemTouchListener() {
        private val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        private var downY = 0f
        private var hidden = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.y
                    hidden = false
                }
                MotionEvent.ACTION_MOVE -> if (!hidden && abs(e.y - downY) > slop) {
                    hidden = true
                    _binding?.searchInput?.hideKeyboard()
                }
            }
            return false
        }
    }

    private var renderedScope: GlobalSearchContract.Scope? = null
    
    private var scrollToTopPending = false

    private fun render(state: GlobalSearchContract.State) {
        
        if (renderedScope != null && renderedScope != state.scope) scrollToTopPending = true
        renderedScope = state.scope
        resultsAdapter.submitList(state.rows) {
            if (scrollToTopPending) {
                scrollToTopPending = false
                _binding?.vContent?.scrollToPosition(0)
            }
        }
    }

    
    override fun onUncovered() {
        _binding?.searchInput?.focusAndShowKeyboard()
    }

    
    override fun onCovered() {
        _binding?.searchInput?.hideKeyboard()
    }

    
    override fun showSelection(selectedId: String?) {
        resultsAdapter.selectedId = selectedId
    }

    
    private fun openHistory(data: SearchChatHistoryViewData) {
        val isGroup = data.type == SearchChatHistoryViewData.Type.Group
        when {
            !data.onlyOneResult -> detailNavigation().push(this, SearchMessageDestination(data.conversationId, isGroup, key = viewModel.state.value.query))
            isGroup -> openGroup(data.conversationId, data.messageTimeStamp)
            else -> openOneOnOne(data.conversationId, data.messageTimeStamp)
        }
    }

    private fun openOneOnOne(contactId: String, jumpTimestamp: Long? = null) {
        
        hideDetailKeyboardBeforeLeaving()
        ChatActivity.startActivity(requireActivity(), contactId, jumpMessageTimeStamp = jumpTimestamp)
    }

    private fun openGroup(groupId: String, jumpTimestamp: Long? = null) {
        hideDetailKeyboardBeforeLeaving()
        GroupChatContentActivity.startActivity(requireActivity(), groupId, jumpMessageTimeStamp = jumpTimestamp)
    }

    companion object {
        private const val CLOSE_SIZE_DP = 48f

        fun newInstance(): GlobalSearchFragment = GlobalSearchFragment()
    }
}
