package com.difft.android.chat.recent

import com.difft.android.base.utils.avoidCornerCutout
import com.difft.android.base.ui.topchrome.TopChromeOwner
import com.difft.android.base.ui.topchrome.TopChromeStyle
import com.difft.android.base.ui.sidechrome.padForSideChrome
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.base.ui.sidechrome.sideChromeBar
import com.difft.android.base.ui.sidechrome.SideChromeViewBar
import com.difft.android.base.widget.floatAsTopBarButton
import com.difft.android.base.widget.floatAsTopBarTextButton
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.glass.installAppBarOverlayChrome
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.call.CallData
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.base.utils.padBottomForHomeTabBar
import com.difft.android.chat.common.HomeHeaderCollapser
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ChativePopupView
import com.difft.android.base.widget.ChativePopupWindow
import com.difft.android.call.manager.CallDataManager
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatFragmentRecentChatBinding
import com.difft.android.chat.group.create.openCreateGroupFromList
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.invite.InviteCodeActivity
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.invite.ScanActivity
import com.difft.android.chat.recent.RoomViewData.Type
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.ui.compose.e2ee.E2eeInfoSheet
import com.difft.android.network.UrlManager
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onEach

import kotlinx.coroutines.withContext
import org.difft.app.database.cache.OfficialAccountCache
import com.difft.android.chat.focus.FocusActionLauncher
import com.difft.android.chat.focus.invalid.FocusInvalidController
import com.difft.android.chat.focus.invalid.bindFocusStatusStrip
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.websocket.WebSocketManager
import javax.inject.Inject
import dagger.Lazy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.difft.android.base.migration.LegacyMigrationGate
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.ui.theme.DifftTheme
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.ui.smoothScrollToPositionWithHelper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class RecentChatFragment : Fragment(), DualPaneSelectionListener, TopChromeOwner {

    override val topChromeStyle: TopChromeStyle get() = TopChromeStyle.Band


    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var webSocketManager: WebSocketManager

    @Inject
    lateinit var dbRoomStore: DBRoomStore

    @Inject
    lateinit var callDataManagerLazy: Lazy<CallDataManager>

    @Inject
    lateinit var proxyConfigProvider: ProxyConfigProvider

    @Inject
    lateinit var urlManager: UrlManager

    @Inject
    lateinit var focusActionLauncher: FocusActionLauncher

    @Inject
    lateinit var focusInvalidController: FocusInvalidController

    private lateinit var binding: ChatFragmentRecentChatBinding
    private var popupWindow: PopupWindow? = null
    private lateinit var popupItemList: MutableList<ChativePopupView.Item>
    private val recentChatViewModel: RecentChatViewModel by viewModels(ownerProducer = { requireActivity() })

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    private val rowActions: ChatListRowActions by lazy {
        ChatListRowActions(requireActivity(), recentChatViewModel, focusActionLauncher, globalServices.myId)
    }

    private val mAdapter: RecentChatAdapter by lazy {
        object : RecentChatAdapter(requireActivity()) {
            override fun onReadToggle(roomViewData: RoomViewData) = rowActions.toggleRead(roomViewData)

            override fun onPinToggle(roomViewData: RoomViewData) = rowActions.togglePin(roomViewData)

            override fun onDeleteRequested(roomViewData: RoomViewData) = rowActions.confirmDelete(roomViewData)

            override fun onFocusToggle(roomViewData: RoomViewData) = rowActions.toggleFocus(roomViewData)

            
            override fun onFoldedGroupsClicked() {
                if ((activity as? ListPaneLayerOpener)?.openFoldedGroupsLayer() != true) {
                    L.w { "[ChatList] folded_open_ignored reason=no_opener" }
                }
            }

            override fun onFoldedGroupsLongClicked(view: View, touchX: Int, touchY: Int) =
                rowActions.showFoldedGroupsActions(view, touchX, touchY)

            override fun onFoldedGroupsEndFocus() = rowActions.endFocus()

            override fun onItemClicked(
                roomViewData: RoomViewData,
                position: Int
            ) {
                
                if (roomViewData.isInstantCall) {
                    return
                }

                
                val navigationCallback = activity as? ConversationNavigationCallback

                when (roomViewData.type) {
                    is Type.OneOnOne -> {
                        L.d { "[ChatList] onItemClicked - OneOnOne ChattingRoom" }
                        if (navigationCallback != null) {
                            navigationCallback.onOneOnOneConversationSelected(roomViewData.roomId)
                            if (navigationCallback.isDualPaneMode) {
                                selectedId = roomViewData.roomId
                            }
                        } else {
                            ChatActivity.startActivity(
                                requireActivity(),
                                roomViewData.roomId
                            )
                        }
                    }

                    is Type.Group -> {
                        L.d { "[ChatList] onItemClicked - Group ChattingRoom" }
                        if (navigationCallback != null) {
                            navigationCallback.onGroupConversationSelected(roomViewData.roomId)
                            if (navigationCallback.isDualPaneMode) {
                                selectedId = roomViewData.roomId
                            }
                        } else {
                            GroupChatContentActivity.startActivity(
                                requireActivity(),
                                roomViewData.roomId
                            )
                        }
                    }
                }
            }


            override fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int) {
                if (roomViewData.hasOngoingCall) return
                rowActions.showItemActions(view, roomViewData, touchX, touchY)
            }

            override fun onFooterClicked() {
                L.i { "[ChatList] E2EE footer hint tapped" }
                val darkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                E2eeInfoSheet.show(
                    activity = requireActivity(),
                    darkTheme = darkTheme,
                    learnMoreUrl = urlManager.e2eeLearnMoreUrl,
                )
            }

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerCallingListener()
    }

    private fun registerCallingListener() {
        callDataManager.callingList
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChanged()
            .onEach { callingList ->
                L.d { "[Call] RecentChatFragment registerCallingListener: callingList = $callingList" }
                assembleAndSubmit(recentChatViewModel.chatListSource.value, callingList)
            }
            .launchIn(lifecycleScope)
    }

    private fun submitSortedChatRooms(rows: List<ListItem>) {
        val items = mutableListOf<ListItem>()
        items.addAll(rows)
        items.add(ListItem.E2eeFooter) 
        mAdapter.submitListKeepingTop(binding.recyclerViewRecentMessage, items)
    }

    
    private suspend fun assembleAndSubmit(source: ChatListSource, callingList: Map<String, CallData>?) {
        val input = ChatListInput(
            rooms = source.rooms,
            callingList = callingList,
            instantCallCache = recentChatViewModel.instantCallRoomViewData.toMap(),
            instantCallDefaultName = getString(com.difft.android.call.R.string.call_instant_call_title_default),
            myId = globalServices.myId,
            now = System.currentTimeMillis(),
            focus = source.focus,
            synthesizedFocusRoom = source.synthesizedFocusRoom,
            deletedFocusGids = source.deletedFocusGids,
        )
        val assembly = withContext(Dispatchers.Default) { ChatListAssembler.assemble(input) }
        if (!isAdded || view == null || !this::binding.isInitialized) return
        recentChatViewModel.instantCallRoomViewData.clear()
        recentChatViewModel.instantCallRoomViewData.putAll(assembly.instantCallCache)
        submitSortedChatRooms(assembly.rows)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = ChatFragmentRecentChatBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initActions()
        binding.focusStatusStrip.bindFocusStatusStrip(focusInvalidController) { focusActionLauncher.openNotificationSettings(requireActivity()) }

        val collapsibleHeader = HomeHeaderCollapser.isEligible(requireActivity())
        HomeHeaderCollapser.bind(binding.homeAppBar, binding.homeHeader, collapsibleHeader)
        binding.recyclerViewRecentMessage.apply {
            this.adapter = mAdapter
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
            addItemDecoration(ChatListDividerDecoration(requireContext()))
            padBottomForHomeTabBar(
                viewLifecycleOwner,
                activity as? DualPaneHost,
                extraPx = HomeHeaderCollapser.collapseRangePx(binding.homeHeader, collapsibleHeader),
            )
        }
        binding.homeHeader.avoidCornerCutout()
        
        
        installAppBarOverlayChrome(
            viewLifecycleOwner, binding.root, binding.homeAppBar,
            binding.recyclerViewRecentMessage.parent as View, binding.recyclerViewRecentMessage,
            covered = (activity as? ConversationNavigationCallback)?.detailCoversList,
            windowLifecycle = requireActivity().lifecycle,
        )
        (binding.chatTitle.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).apply {
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            binding.chatTitle.layoutParams = this
        }
        if (GlassMode.isLive(requireContext())) {
            
            
            binding.addOptions.floatAsTopBarButton(leading = false, glass = true)
            binding.ivProxyStatus.floatAsTopBarButton(leading = false, glass = true)
        }

        recentChatViewModel.retrieveCallingList()

        
        (activity as? ConversationNavigationCallback)?.detailCoversList
            ?.onEach {
                listVisibility.onCoveredChanged(it)
                updateHeaderEligibility()
            }
            ?.launchIn(viewLifecycleOwner.lifecycleScope)

        observeChatWSConnection()

        recentChatViewModel.chatListSource
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { source -> assembleAndSubmit(source, callDataManager.getCallListData()) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        recentChatViewModel.createNote()

        RecentChatUtil.chatDoubleTab
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach {
                val layoutManager = binding.recyclerViewRecentMessage.layoutManager as? LinearLayoutManager ?: return@onEach
                val firstVisible = layoutManager.findFirstVisibleItemPosition()

                val firstUnreadItemPosition = mAdapter.currentList.indexOfFirst { data ->
                    data.chatItemData()?.isMuted == false && (data.chatItemData()?.displayUnreadNum ?: 0) > 0
                }

                val canScrollDown = binding.recyclerViewRecentMessage.canScrollVertically(1)

                var nextUnreadItemPosition = if (!canScrollDown) {
                    firstUnreadItemPosition
                } else {
                    mAdapter.currentList
                        .drop(firstVisible + 1)
                        .indexOfFirst { data ->
                            data.chatItemData()?.isMuted == false && (data.chatItemData()?.displayUnreadNum ?: 0) > 0
                        }
                        .let { if (it != -1) it + firstVisible + 1 else firstUnreadItemPosition }
                }
                if (nextUnreadItemPosition == -1) {
                    nextUnreadItemPosition = 0
                }
                
                
                if (nextUnreadItemPosition == 0) binding.homeAppBar.setExpanded(true, true)
                binding.recyclerViewRecentMessage.post {
                    if (canScrollDown) {
                        binding.recyclerViewRecentMessage.smoothScrollToPositionWithHelper(requireContext(), nextUnreadItemPosition, 100f)
                    } else {
                        binding.recyclerViewRecentMessage.scrollToPosition(nextUnreadItemPosition)
                    }
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        TextSizeUtil.textSizeState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { mAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        
        
        OfficialAccountCache.state
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { mAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    mAdapter.updateCallBarTick()
                    delay(1000)
                }
            }
        }
    }

    private val listVisibility = ConversationListVisibility(onReturnedToList = { onReturnedToList() })

    override fun onResume() {
        super.onResume()

        listVisibility.onResumedChanged(true)
        updateHeaderEligibility()
        onReturnedToList()
    }

    




    private fun onReturnedToList() {
        
        updateProxyStatusIcon()
        refreshLegacyMigrationEntry()

        recentChatViewModel.retrieveCallingList()
        
        recentChatViewModel.refreshIfFormatChanged()

        
        val navigationCallback = activity as? ConversationNavigationCallback
        if (navigationCallback?.isDualPaneMode == true) {
            mAdapter.selectedId = navigationCallback.currentSelectedConversationId
        }
        
        
        
        view?.requestLayout()
    }

    override fun updateDualPaneSelection(selectedId: String?) {
        mAdapter.selectedId = selectedId
    }

    override fun onPause() {
        super.onPause()

        listVisibility.onResumedChanged(false)
    }

    private fun initActions() {
        popupItemList = mutableListOf<ChativePopupView.Item>().apply {
            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_create_group),
                    label = getString(R.string.chat_option_create_group)
                ) {
                    openCreateGroupFromList(this@RecentChatFragment)
                    popupWindow?.dismiss()
                })
            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_add_contact),
                    label = getString(R.string.chat_option_add_contact)
                ) {
                    InviteCodeActivity.startActivity(requireActivity())
                    popupWindow?.dismiss()
                })
            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_my_code),
                    label = getString(R.string.invite_my_code)
                ) {
                    inviteUtils.showInviteDialog(requireActivity())
                    popupWindow?.dismiss()
                })
            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_scan),
                    label = getString(R.string.chat_option_scan)
                ) {
                    ScanActivity.startActivity(requireActivity())
                    popupWindow?.dismiss()
                })

            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_mark_all_as_read),
                    label = getString(R.string.chat_option_mark_all_as_read)
                ) {
                    recentChatViewModel.markAllAsRead(requireActivity())
                    popupWindow?.dismiss()
                })
        }
        val showAddOptions = {
            
            val onBar = headerBar?.barBoundsInWindow(ACTION_ADD_OPTIONS)
            popupWindow = if (onBar != null) {
                ChativePopupWindow.showBesideBarButton(binding.root, onBar, popupItemList)
            } else {
                ChativePopupWindow.showAsDropDown2(binding.addOptions, popupItemList)
            }
        }
        binding.addOptions.setOnClickListener { showAddOptions() }
        setupLegacyMigrationEntry()
        binding.ivProxyStatus.setOnClickListener { openProxySettings() }
        
        
        if (!isInDualPaneMode()) padForSideChrome(binding.root)
        headerBar = if (isInDualPaneMode()) null else sideChromeBar(base = true).also { bar ->
            bar.setAction(ACTION_ADD_OPTIONS, binding.addOptions, R.drawable.chat_add_options, getString(R.string.chat_add_options), visible = true, onClick = showAddOptions)
        }
        if (headerBar == null) {
            binding.addOptions.floatAsTopBarButton(leading = false, glass = true)
            binding.ivProxyStatus.floatAsTopBarButton(leading = false, glass = true)
        }
        updateHeaderEligibility()
        updateProxyStatusIcon()
    }

    private var headerBar: SideChromeViewBar? = null

    private fun updateHeaderEligibility() {
        headerBar?.setEligible((activity as? ConversationNavigationCallback)?.detailCoversList?.value != true)
    }

    private fun openProxySettings() {
        startActivity(Intent(requireContext(), globalServices.activityProvider.getActivityClass(ActivityType.PROXY_SETTINGS)))
    }

    
    private var lastWebSocketState: WebSocketConnectionState? = null

    











    private fun updateProxyStatusIcon() {
        
        
        
        
        if (view == null) return
        val icon = when (lastWebSocketState) {
            WebSocketConnectionState.CONNECTED -> R.drawable.chat_ic_proxy_shield_connected
            WebSocketConnectionState.CONNECTING, null -> R.drawable.chat_ic_proxy_shield_checking
            else -> R.drawable.chat_ic_proxy_shield_unavailable
        }
        binding.ivProxyStatus.setImageResource(icon)
        val bar = headerBar
        if (bar != null) {
            bar.setAction(
                ACTION_PROXY, binding.ivProxyStatus, icon, getString(R.string.chat_proxy_status),
                visible = proxyConfigProvider.isEnabled, onClick = ::openProxySettings, tinted = false,
            )
        } else {
            binding.ivProxyStatus.visibility = if (proxyConfigProvider.isEnabled) View.VISIBLE else View.GONE
        }
    }

    private fun observeChatWSConnection() {
        webSocketManager.getWebSocketConnection()
            .webSocketConnectionState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChanged()
            .onEach { state ->
                L.i { "[ws][RecentChatFragment] chat webSocketConnectionState changed:$state" }
                lastWebSocketState = state
                updateProxyStatusIcon()
                when (state) {
                    
                    
                    WebSocketConnectionState.DISCONNECTED,
                    WebSocketConnectionState.FAILED,
                    WebSocketConnectionState.AUTHENTICATION_FAILED,
                    WebSocketConnectionState.UNKNOWN_HOST_FAILED,
                    WebSocketConnectionState.INACTIVE_FAILED,
                        -> {
                        binding.chatTitle.text = getString(R.string.chat_state_offline)
                    }

                    WebSocketConnectionState.CONNECTING,
                        -> {
                        binding.chatTitle.text = getString(R.string.chat_state_connecting)
                    }

                    WebSocketConnectionState.CONNECTED -> {
                        binding.chatTitle.text = getString(R.string.chat_chats)
                    }

                    else -> {
                        binding.chatTitle.text = getString(R.string.chat_chats)
                    }
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    





    private fun setupLegacyMigrationEntry() {
        if (!PeerApps.isLegacy(requireContext())) return
        binding.migrateEntry.setContent {
            DifftTheme(applyWindowBackground = false) { LegacyMigrationEntryChip(onClick = ::openLegacyMigrationGuide, glass = true) }
        }
        refreshLegacyMigrationEntry()
    }

    private fun refreshLegacyMigrationEntry() {
        if (!PeerApps.isLegacy(requireContext())) return
        val open = LegacyMigrationGate.isOpen(requireContext(), globalConfigsManager)
        binding.migrateEntry.visibility = if (open) View.VISIBLE else View.GONE
        
        
    }

    private fun openLegacyMigrationGuide() {
        startActivity(Intent().setClassName(requireContext().packageName, PeerApps.LEGACY_GUIDE_ACTIVITY))
    }

    private companion object {
        const val ACTION_ADD_OPTIONS = "add_options"
        const val ACTION_PROXY = "proxy"
    }
}
