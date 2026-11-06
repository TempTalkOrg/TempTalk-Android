package com.difft.android.chat.recent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.call.manager.CallDataManager
import com.difft.android.chat.R
import com.difft.android.chat.focus.FocusActionLauncher
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.group.GroupChatContentActivity
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "Folded Groups" (PRD 7.4.2) as the root of the home screen's list-pane layer: the groups the
 * focus folds, pinned first (with their pinned background) and then by time, on the chat list's
 * own adapter and row actions. Groups with an ongoing call are not here (the main list shows
 * them). A group opens in the current tab's detail stack and its row shows the two-pane selection.
 * The page closes the layer on its own when focus ends or folding is turned off.
 */
@AndroidEntryPoint
class FoldedGroupsFragment : Fragment(), DualPaneSelectionListener {

    @Inject
    lateinit var focusRepository: GroupFocusRepository

    @Inject
    lateinit var focusActionLauncher: FocusActionLauncher

    @Inject
    lateinit var callDataManager: Lazy<CallDataManager>

    @Inject
    lateinit var chatListSourceProvider: ChatListSourceProvider

    // The home screen's instance, shared with the chat list: the same Activity-scoped ViewModel the
    // rows come from through chatListSourceProvider, so row actions and rows never disagree.
    private val viewModel: RecentChatViewModel by viewModels(ownerProducer = { requireActivity() })

    private val rowActionsDelegate = lazy { ChatListRowActions(requireActivity(), viewModel, focusActionLauncher, globalServices.myId) }
    private val rowActions by rowActionsDelegate

    private var selectedId: String? = null

    private val adapter: RecentChatAdapter by lazy {
        object : RecentChatAdapter(requireActivity()) {
            override fun onItemClicked(roomViewData: RoomViewData, position: Int) {
                GroupChatContentActivity.startActivity(requireActivity(), roomViewData.roomId)
            }

            override fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int) {
                if (roomViewData.hasOngoingCall) return
                rowActions.showItemActions(view, roomViewData, touchX, touchY)
            }

            override fun onReadToggle(roomViewData: RoomViewData) = rowActions.toggleRead(roomViewData)

            override fun onPinToggle(roomViewData: RoomViewData) = rowActions.togglePin(roomViewData)

            override fun onDeleteRequested(roomViewData: RoomViewData) = rowActions.confirmDelete(roomViewData)

            override fun onFocusToggle(roomViewData: RoomViewData) = rowActions.toggleFocus(roomViewData)
        }.also { it.selectedId = selectedId }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        closeWhenFoldingEnds()
    }

    /**
     * Pops this page (the layer's root, so the layer closes) once focus is not active with folding.
     * Starts after STARTED: a restored page is created inside the Activity's onCreate, before the
     * layer it closes exists. Judges the loaded focus only: before the cache loads, `effective` is
     * INACTIVE. Then collects without a lifecycle gate, so the page also closes under a one-pane
     * conversation layer. The page pushes nothing, so it is always its host's top page.
     */
    private fun closeWhenFoldingEnds() {
        lifecycleScope.launch {
            lifecycle.withStarted {}
            focusRepository.ensureLoaded()
            var popped = false
            focusRepository.effective.collect { focus ->
                // One pop: the layer removes this page on its queue, later emissions must not repeat it.
                if (popped || (focus.active && focus.foldOthers) || !isAdded) return@collect
                popped = true
                L.i { "[ChatList] folded_layer_close active=${focus.active} fold=${focus.foldOthers}" }
                detailNavigation().pop(this@FoldedGroupsFragment)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = getString(R.string.focus_list_folded_screen_title)
        // TalkBack announces the page when the layer opens, as it did for the window of a new screen.
        ViewCompat.setAccessibilityPaneTitle(view, title)
        (view as ComposeView).setContent {
            DifftTheme(applyWindowBackground = false) {
                Column(Modifier.fillMaxSize().background(DifftTheme.colors.background).detailPageInsets(containerPadsBottom())) {
                    TitleBar(
                        titleText = title,
                        onBackClick = { detailNavigation().pop(this@FoldedGroupsFragment) },
                        showBackButton = showsDetailBackButton(),
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            RecyclerView(context).apply {
                                layoutManager = LinearLayoutManager(context)
                                itemAnimator = null
                                adapter = this@FoldedGroupsFragment.adapter
                                // Same rows as the chat list, same hairline between them.
                                addItemDecoration(ChatListDividerDecoration(context))
                            }
                        },
                    )
                }
            }
        }
        observeRows()
    }

    private fun observeRows() {
        val source = chatListSourceProvider.chatListSource(requireActivity())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(source.filter { it.isLoaded }, callDataManager.get().callingList) { loaded, calls -> loaded to calls }
                    .collect { (loaded, calls) ->
                        val input = ChatListInput(
                            rooms = loaded.rooms,
                            callingList = calls,
                            instantCallCache = emptyMap(),
                            instantCallDefaultName = "",
                            myId = globalServices.myId,
                            now = System.currentTimeMillis(),
                            focus = loaded.focus,
                            synthesizedFocusRoom = loaded.synthesizedFocusRoom,
                            deletedFocusGids = loaded.deletedFocusGids,
                        )
                        val folded = withContext(Dispatchers.Default) { ChatListAssembler.assemble(input).folded }
                        adapter.submitList(folded.map { ListItem.ChatItem(it) })
                    }
            }
        }
    }

    override fun updateDualPaneSelection(selectedId: String?) {
        this.selectedId = selectedId
        // Before the view exists the adapter is not built yet; it picks the stored id up when it is.
        if (view != null) adapter.selectedId = selectedId
    }

    override fun onDestroyView() {
        // The row popup is an Activity-window object and would outlive a page the layer removed.
        if (rowActionsDelegate.isInitialized()) rowActions.dismissPopup()
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): FoldedGroupsFragment = FoldedGroupsFragment()
    }
}
