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

    






    private fun closeWhenFoldingEnds() {
        lifecycleScope.launch {
            lifecycle.withStarted {}
            focusRepository.ensureLoaded()
            var popped = false
            focusRepository.effective.collect { focus ->
                
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
        
        if (view != null) adapter.selectedId = selectedId
    }

    override fun onDestroyView() {
        
        if (rowActionsDelegate.isInitialized()) rowActions.dismissPopup()
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): FoldedGroupsFragment = FoldedGroupsFragment()
    }
}
