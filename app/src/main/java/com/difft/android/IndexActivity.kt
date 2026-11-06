package com.difft.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.difft.android.setting.notificationtest.NotificationTestReturnRoute
import com.difft.android.setting.notificationtest.NotificationTestReturns
import com.difft.android.setting.notificationtest.NotificationTestFragment
import com.difft.android.setting.notificationtest.NotificationTestDestination
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Display
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import androidx.activity.viewModels
import androidx.core.animation.doOnEnd
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.DualPaneCollapseUtil
import com.difft.android.base.utils.DualPaneRatioUtil
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.FilePathManager
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.utils.SharedUriGuard
import com.difft.android.base.utils.SizeLimitExceededException
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.copyToBounded
import com.difft.android.base.utils.globalServices
import com.difft.android.base.security.SafeLinkOpener
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.base.utils.NetworkUtils
import com.difft.android.chat.R
import com.difft.android.chat.contacts.ContactsFragment
import com.difft.android.chat.contacts.WeakContactReconciler
import com.difft.android.chat.contacts.contactsdetail.ContactDetailBottomSheetDialogFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.attachment.migration.ForwardAttachmentMigration
import com.difft.android.chat.media.LegacyPlaintextAttachmentMigration
import com.difft.android.chat.widget.AudioAmplitudesHelper
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.chat.media.LegacyPlaintextAvatarCleanup
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupChatFragment
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.recent.DualPaneSelectionListener
import com.difft.android.chat.recent.ListPaneLayerOpener
import com.difft.android.dualpane.DualPaneReconciler
import com.difft.android.dualpane.IndexBackCallback
import com.difft.android.dualpane.IndexDetailPages
import com.difft.android.dualpane.ListPaneLayer
import com.difft.android.dualpane.ListPaneLayerRoot
import com.difft.android.dualpane.OpenConversationExtra
import com.difft.android.dualpane.PaneImeRouter
import com.difft.android.dualpane.PaneNavigator
import com.difft.android.dualpane.PaneSplitBackgroundDrawable
import com.difft.android.home.ChatsBadge
import com.difft.android.home.HomeTabCapsule
import com.difft.android.home.HomeTabCapsuleState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.base.ui.theme.DifftTheme
import kotlinx.coroutines.flow.update
import com.difft.android.dualpane.StaleLayoutMonitor
import com.difft.android.dualpane.StaleLayoutOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.difft.android.chat.recent.RecentChatFragment
import com.difft.android.chat.recent.RecentChatUtil
import com.difft.android.chat.recent.RecentChatViewModel
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.translate.model.TranslateModelStore
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatFragment
import com.difft.android.chat.ui.ChatInputFocusable
import com.difft.android.chat.ui.ChatMessageListFragment
import com.difft.android.chat.ui.ChatMessageListProvider
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.databinding.ActivityIndexBinding
import com.difft.android.login.repo.LoginRepo
import com.difft.android.me.MeFragment
import com.difft.android.network.ServerTimeSyncer
import com.difft.android.network.config.FeatureGrayManager
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.config.UserAgentManager
import com.difft.android.push.FcmInitResult
import com.difft.android.push.PushUtil
import com.difft.android.security.SecurityLib
import com.difft.android.setting.BackgroundConnectionSettingsActivity
import com.difft.android.setting.UpdateManager
import com.difft.android.startup.prompt.FcmFallbackStarter
import com.difft.android.startup.prompt.IndexStartupPromptHost
import com.difft.android.app.TempTalkApplication
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.wcdb
import com.difft.android.chat.messages.FailedMessageProcessor
import com.difft.android.chat.messages.PendingMessageProcessor
import com.difft.android.chat.util.AppIconBadgeManager
import com.difft.android.chat.util.NotificationChannelManager
import com.difft.android.chat.websocket.WebSocketManager
import com.difft.android.views.DraggableDividerView
import java.io.File
import javax.inject.Inject


@AndroidEntryPoint
class IndexActivity :
    BaseActivity(),
    ConversationNavigationCallback,
    ChatMessageListProvider,
    ChatInputFocusable,
    PaneNavigator.Owner,
    ListPaneLayer.Owner,
    ListPaneLayerOpener {
    private lateinit var binding: ActivityIndexBinding

    // Two-pane layout (w673dp-h480dp), read from the resource mirror of the layout gate: both
    // layouts carry detail_pane (the per-tab stacks restore into either tree).
    override var isDualPaneMode = false
        private set
    override val currentSelectedConversationId: String?
        get() = if (::navigator.isInitialized) navigator.currentSelectedId else null

    // Per-tab detail stacks (both layouts); owns the detail pane, the one-pane layer and the
    // root background.
    private lateinit var navigator: PaneNavigator
    override val paneNavigator: PaneNavigator get() = navigator

    // The list pane's layer (search, folded groups; both layouts): over the tab content, next to the navigator.
    override lateinit var listLayer: ListPaneLayer
        private set

    // "The list is covered": the one-pane detail layer or the list-pane layer is up.
    private lateinit var listCovered: StateFlow<Boolean>

    // Two-pane: keyboard insets reach only the pane that holds the focused field.
    private var paneImeRouter: PaneImeRouter? = null

    // Set on the first onRestoreInstanceState; a second one must not replay pager state.
    private var hierarchyStateRestored = false

    // Drives the list-pane collapse/expand snap; owns list_pane's width while running.
    private var paneAnimator: ValueAnimator? = null

    // Rebuilds the pane tree when the window crosses the dual-pane gate WITHOUT an OS relaunch
    // (ColorOS fold/unfold, floating window, split-screen). paneStarted gates recreate(), which
    // is a silent no-op while stopped; onStart is the retry edge for a deferred rebuild.
    private lateinit var paneReconciler: DualPaneReconciler
    private var paneStarted = false

    // Floating home tab capsule (HomeTabCapsule): selected tab, Chats badge, own avatar.
    private val homeTabState = MutableStateFlow(HomeTabCapsuleState())

    // #1454: a fold whose Configuration never arrives (see StaleLayoutMonitor); the panel key a
    // size override was applied for in attachBaseContext, if any.
    private lateinit var staleMonitor: StaleLayoutMonitor
    private var staleOverridePanel: String? = null

    // Height of the capsule host (capsule + gaps + navigation-bar inset); the home pages pad
    // their scrolling content by it so the last row clears the capsule.
    private val homeTabBarObstruction = MutableStateFlow(0)

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var updateManager: UpdateManager

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var loginRepo: LoginRepo

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    private val recentChatViewModel: RecentChatViewModel by viewModels()

    @Inject
    lateinit var appIconBadgeManager: AppIconBadgeManager

    @Inject
    lateinit var environmentHelper: EnvironmentHelper

    @Inject
    lateinit var failedMessageProcessor: FailedMessageProcessor

    @Inject
    lateinit var pendingMessageProcessor: PendingMessageProcessor

    @Inject
    lateinit var webSocketManager: WebSocketManager

    @Inject
    lateinit var conversationSettingsManager: ConversationSettingsManager

    @Inject
    lateinit var groupFocusRepository: GroupFocusRepository

    @Inject
    lateinit var translateModelStore: TranslateModelStore

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var startupPromptHost: IndexStartupPromptHost

    @Inject
    lateinit var fcmFallbackStarter: FcmFallbackStarter

    @Inject
    lateinit var pushUtil: PushUtil

    @Inject
    lateinit var gson: Gson

    @Inject
    lateinit var weakContactReconciler: WeakContactReconciler

    @Inject
    lateinit var serverTimeSyncer: ServerTimeSyncer

    @Inject
    lateinit var forwardAttachmentMigration: ForwardAttachmentMigration

    @Inject
    lateinit var notificationTestReturns: NotificationTestReturns
    private val handledTestReturns = LinkedHashSet<String>()
    private var testReturnGeneration = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledTestReturns.addAll(savedInstanceState?.getStringArrayList("handled_notification_test_returns").orEmpty())
        binding = ActivityIndexBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup dual-pane layout for large screens (w673dp-h480dp)
        setupDualPaneLayout()
        if (isDualPaneMode) paneImeRouter = PaneImeRouter(binding.root).also { it.attach() }
        navigator = PaneNavigator(this, isDualPaneMode, IndexDetailPages, paneListener)
        navigator.attach(savedInstanceState)
        listLayer = ListPaneLayer(this, navigator.chrome, IndexDetailPages, navigator.detailCoversList) {
            if (::startupPromptHost.isInitialized) startupPromptHost.onDetailPaneChanged()
        }
        listLayer.attach(savedInstanceState)
        listCovered = combine(navigator.detailCoversList, listLayer.activeState) { detailLayer, listPaneLayer -> detailLayer || listPaneLayer }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, navigator.detailCoversList.value || listLayer.active)
        paneReconciler = DualPaneReconciler(this, binding.root, isStarted = { paneStarted })
        paneReconciler.attach()
        staleMonitor = StaleLayoutMonitor(this, paneReconciler, isStarted = { paneStarted }, activeOverridePanel = staleOverridePanel)

        mountHomeTabCapsule()

        // Measured here on the main thread: L lambdas run on the logger thread, and first-touching
        // androidx.window there races BaseActivity's FoldPostureWatcher into a class-init deadlock.
        val density = resources.displayMetrics.density
        val swDp = resources.configuration.smallestScreenWidthDp
        val widthDp = (WindowSizeClassUtil.getWindowWidthPx(this) / density).toInt()
        val heightDp = (WindowSizeClassUtil.getWindowHeightPx(this) / density).toInt()
        val dualPane = isDualPaneMode
        L.i { "[IndexActivity] screen swDp=$swDp widthDp=$widthDp heightDp=$heightDp dualPane=$dualPane" }

        // Load and emit text size early to avoid ANR in UI components
        TextSizeUtil.loadAndEmitTextSize()
        DualPaneRatioUtil.loadAndEmit()
        DualPaneCollapseUtil.loadAndEmit()

        TextSizeUtil.textSizeState
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { textSize ->
                val isLarger = textSize == TextSizeUtil.TEXT_SIZE_LAGER
                applyListPaneWidth(isLarger)
            }
            .launchIn(lifecycleScope)

        DualPaneRatioUtil.ratioState
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { applyListPaneWidth(TextSizeUtil.isLarger) }
            .launchIn(lifecycleScope)

        setupDualPaneDivider()
        // A collapsed list over an empty detail pane would be a blank first screen, so a cold
        // start opens it; a recreation (rotate / fold) keeps the user's collapse.
        if (savedInstanceState == null && isDualPaneMode) expandListPane(animate = false)

        setupViewPager(restoring = savedInstanceState != null)
        observeMyContactForHomeTab()

        // Handle Intent data (deeplink or external share)
        handleIntentData(intent, isRecreation = savedInstanceState != null)
        recordUA()

        initWCDB()

        // Weak-contact cold-start reconcile: fetch deletedRecords, full overwrite + diff side-effects.
        // Placed after initWCDB() so the weak table is mounted; reconcile is Mutex-serialized internally
        // and concurrency-safe against WS notify.
        lifecycleScope.launch(Dispatchers.IO) { weakContactReconciler.reconcile("coldStart") }

        startReceivingMessages()

        processPendingAndFailedMessages()

        WCDBUpdateService.start()

        cleanEmptyRooms()

        initFCMPush()

        observeFcmInitResult()

        updateManager.attach(this)

        // Gates and the one startup prompt per session (#1355): security warnings, forced/optional
        // update, notifications, background connection, full-screen intent.
        startupPromptHost.attach(
            activity = this,
            updateManager = updateManager,
            lockPending = (application as TempTalkApplication).screenLockPendingState,
            // No prompt over a conversation (two-pane detail content, or the one-pane layer) or over the list-pane layer.
            dualPaneConversationOpen = {
                (isDualPaneMode && (navigator.currentSelectedId != null || navigator.depth() > 1)) ||
                    navigator.detailCoversList.value || listLayer.active
            },
        )

        syncContactAndGroupInfo()

        setUserProfile()

        checkDisappearingMessage()

        startFileCleanupTask()

        observeAndUpdateUnreadMessageCountBadge()

        globalConfigsManager.syncMineConfigs()

        // Sync conversation settings and the group focus state
        syncConversationAndFocusState()

        // Reconcile the auto-translate models with what the conversations actually need. Costs one
        // SQL query and returns before touching ML Kit when no conversation has the setting on.
        translateModelStore.syncOnStart()

        // The only Activity-level Back callback; hosted pages register theirs with
        // detailBackOwner(), so the visible top page's callbacks run first.
        onBackPressedDispatcher.addCallback(
            this,
            IndexBackCallback(
                navigator = navigator,
                listLayer = listLayer,
                isListCollapsed = { isDualPaneMode && DualPaneCollapseUtil.isCollapsed },
                expandList = { expandListPane() },
                moveTaskToBack = { moveTaskToBack(true) },
            )
        )

        fetchCallServiceUrlAndCache()

        fetchFeatureGrayConfigs()
    }

    private fun setupViewPager(restoring: Boolean) {
        binding.indexViewpager.apply {
            offscreenPageLimit = 1
            isUserInputEnabled = false
            // Disable overscroll effect in dual-pane mode
            if (isDualPaneMode) {
                overScrollMode = View.OVER_SCROLL_NEVER
                // Also disable on internal RecyclerView
                (getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            }
            adapter = object : FragmentStateAdapter(this@IndexActivity) {
                private val fragmentClasses =
                    listOf(
                        RecentChatFragment::class.java,
                        ContactsFragment::class.java,
                        MeFragment::class.java,
                    )

                override fun getItemCount(): Int = fragmentClasses.size

                override fun createFragment(position: Int): Fragment {
                    val fragmentClass = fragmentClasses[position]
                    val fragment = fragmentClass.newInstance()
                    return fragment
                }
            }

            // Start on the navigator's restored tab: ViewPager2 restores its own item only later,
            // and the first onPageSelected below must not switch the restored stacks away.
            if (restoring) setCurrentItem(navigator.currentTab, false)

            registerOnPageChangeCallback(object : OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    // position 0 是会话列表，不允许截屏
//                    ScreenShotUtil.refreshWithPagePolicy(this@IndexActivity, position != 0)
                    selectIndicator(position)
                    // Shows this tab's detail stack and hides the others; the navigator also
                    // repaints the root background for the tab.
                    navigator.selectTab(position)
                }
            }.also {
                // 初始化时手动触发一次，因为 OnPageChangeCallback 默认不会触发第一页
                it.onPageSelected(currentItem)
            })

        }
    }

    /** Last logged (notMuted, muted, dotNotMuted, dotMuted) so the badge log only fires on change. */
    private var lastLoggedBadgeState: List<Int>? = null

    private fun observeAndUpdateUnreadMessageCountBadge() {
        recentChatViewModel.allRecentRoomsStateFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { rooms ->
                // A manual "mark as unread" is never counted (#1332, chat-list gesture design); it
                // only earns a dot on the tab when no real unread exists in that mute group.
                val (notMuted, muted) = rooms.partition { room -> room.isMuted.not() }
                val unreadNotMuteMessageCount = notMuted.sumOf { room -> room.unreadMessageNum }
                val unreadMuteMessageCount = muted.sumOf { room -> room.unreadMessageNum }
                val manualNotMuted = notMuted.any { room -> room.isManualUnread && room.unreadMessageNum == 0 }
                val manualMuted = muted.any { room -> room.isManualUnread && room.unreadMessageNum == 0 }
                val badgeState = listOf(unreadNotMuteMessageCount, unreadMuteMessageCount, if (manualNotMuted) 1 else 0, if (manualMuted) 1 else 0)
                if (badgeState != lastLoggedBadgeState) {
                    lastLoggedBadgeState = badgeState
                    L.i { "[IndexActivity] unread badge notMuted=$unreadNotMuteMessageCount muted=$unreadMuteMessageCount dotNotMuted=$manualNotMuted dotMuted=$manualMuted" }
                }
                val badge = when {
                    unreadNotMuteMessageCount > 0 -> ChatsBadge.Count(unreadNotMuteMessageCount, muted = false)
                    manualNotMuted -> ChatsBadge.Dot(muted = false)
                    unreadMuteMessageCount > 0 -> ChatsBadge.Count(unreadMuteMessageCount, muted = true)
                    manualMuted -> ChatsBadge.Dot(muted = true)
                    else -> ChatsBadge.None
                }
                homeTabState.update { it.copy(chatsBadge = badge) }
                userManager.update { unreadMsgNum = unreadNotMuteMessageCount }
                appIconBadgeManager.updateAppIconBadgeNum(unreadNotMuteMessageCount)
            }
            .launchIn(lifecycleScope)
    }

    /**
     * Mounts [HomeTabCapsule] into both layouts' `home_tab_capsule` host. The list column runs
     * under the navigation bar in both layouts (see shouldApplyNavigationBarPadding), so the
     * host keeps its own gap above the bar. The host's laid-out height feeds
     * [homeTabBarObstructionPx] so the home pages can scroll their last row clear of it.
     */
    private fun mountHomeTabCapsule() {
        // iOS keeps the chrome 28dp above the screen bottom (over the home indicator). The host
        // sees the navigation-bar inset: gesture navigation (24) lands on the same 28; a
        // 3-button bar must stay clear, so the gap grows to the inset plus 4.
        val gapPx = (HOME_TAB_BAR_BOTTOM_GAP_DP * resources.displayMetrics.density).toInt()
        val clearancePx = (HOME_TAB_BAR_NAV_CLEARANCE_DP * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeTabBar) { view, windowInsets ->
            val navBottom = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            view.updatePadding(bottom = maxOf(gapPx, navBottom + clearancePx))
            windowInsets
        }
        binding.homeTabBar.requestApplyInsets()
        binding.homeTabCapsule.setContent {
            // Embedded composition: the host window keeps its own background (#1131).
            DifftTheme(applyWindowBackground = false) {
                val state by homeTabState.collectAsStateWithLifecycle()
                HomeTabCapsule(
                    state = state,
                    onTabClick = ::onHomeTabClick,
                    onChatsDoubleTap = RecentChatUtil::emitChatDoubleTab,
                    onSearch = ::onHomeSearchClick,
                )
            }
        }
        binding.homeTabBar.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            homeTabBarObstruction.value = if (v.visibility == View.VISIBLE) bottom - top else 0
        }
    }

    // Same reveal rule as a tab tap: search must not open inside a list pane that is collapsing.
    private fun onHomeSearchClick() {
        if (isDualPaneMode && DualPaneCollapseUtil.isCollapsed) {
            expandListPane()
        }
        openSearchLayer()
    }

    private fun onHomeTabClick(index: Int) {
        // Tapping a tab is the discoverable way back from a collapsed list pane (the capsule
        // collapses with the list, so this only fires while it is being revealed); the
        // divider handle and Back remain.
        if (isDualPaneMode && DualPaneCollapseUtil.isCollapsed) {
            expandListPane()
        }
        // The list-pane layer is global, not per tab: a tab tap leaves it, as iOS hides the tab bar under search.
        listLayer.close()
        binding.indexViewpager.setCurrentItem(index, false)
    }

    /** The Me tab shows the user's own avatar; MeFragment reads the same contact the same way. */
    private fun observeMyContactForHomeTab() {
        lifecycleScope.launch { loadMyContactForHomeTab() }
        ContactorUtil.contactsUpdate
            .onEach { updatedIds -> if (updatedIds.contains(globalServices.myId)) loadMyContactForHomeTab() }
            .launchIn(lifecycleScope)
    }

    private suspend fun loadMyContactForHomeTab() {
        try {
            val result = withContext(Dispatchers.IO) { ContactorUtil.getContactWithID(this@IndexActivity, globalServices.myId) }
            homeTabState.update { it.copy(me = result.orElse(null)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[IndexActivity] home tab own contact load failed: ${e.message}" }
        }
    }

    /**
     * Skips the pager's state restore once its adapter can no longer accept one — a restore
     * already ran, or a layout pass bound a page first. Otherwise
     * `FragmentStateAdapter.restoreState` throws "Expected the adapter to be 'fresh'"
     * (Crashlytics bc077db1).
     *
     * Uses `isSaveFromParentEnabled` rather than catching the exception: ViewPager2 clears its
     * pending adapter state only after `restoreState()` returns and re-saves it from
     * `onSaveInstanceState()`, so a caught exception would carry the rejected bundle into the
     * next recreation. The flag is re-armed after the dispatch — the same flag also gates
     * `dispatchSaveInstanceState`, so leaving it off would stop this instance from ever saving
     * the pager's state again.
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        val pager = binding.indexViewpager
        val boundPages = (pager.getChildAt(0) as? RecyclerView)?.childCount ?: 0
        val skipPagerRestore = hierarchyStateRestored || boundPages > 0
        if (skipPagerRestore) {
            L.w { "[IndexActivity] skip pager state restore restoredBefore=$hierarchyStateRestored boundPages=$boundPages" }
            pager.isSaveFromParentEnabled = false
        }
        hierarchyStateRestored = true
        try {
            super.onRestoreInstanceState(savedInstanceState)
        } finally {
            if (skipPagerRestore) pager.isSaveFromParentEnabled = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentData(intent)
    }

    /** Full conversation-settings pull plus the group focus GET (the only source of its feature switch). */
    private fun syncConversationAndFocusState() {
        conversationSettingsManager.syncConversationSettings()
        groupFocusRepository.requestRefresh("index-start")
    }

    private fun syncContactAndGroupInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            ContactorUtil.fetchAndSaveContactors(false)
            groupUtil.syncAllGroupAndAllGroupMembers(forceFetch = false, syncMembers = true)
        }
    }

    private fun recordUA() {
        L.i { "[UA] ======>" + UserAgentManager.getUserAgent() + "===uid:" + globalServices.myId }
    }

    private fun startReceivingMessages() {
        webSocketManager.start()
    }

    private fun processPendingAndFailedMessages() {
        pendingMessageProcessor.triggerProcess()
        failedMessageProcessor.triggerProcess()
    }


    private fun selectIndicator(indicatorPosition: Int) {
        homeTabState.update { it.copy(selected = indicatorPosition) }
    }

    // A collapsed list pane would leave the list/detail line beside the rail's own divider.
    private fun syncListDetailDivider() {
        val listPane = binding.root.findViewById<View>(com.difft.android.R.id.list_pane) ?: return
        val line = binding.root.findViewById<View>(com.difft.android.R.id.divider_list_detail) ?: return
        val visibility = if (listPane.width > COLLAPSED_LIST_WIDTH_PX) View.VISIBLE else View.INVISIBLE
        if (line.visibility != visibility) line.visibility = visibility
    }

    /**
     * Never: in both layouts the list and detail columns run under the navigation bar. The
     * floating tab capsule keeps its own gap above it ([mountHomeTabCapsule]) and every page
     * in a pane or layer pads its own bottom (`containerPadsBottom()` is false there), the same
     * way a one-pane page always has.
     */
    override fun shouldApplyNavigationBarPadding(): Boolean = false

    /** #1454 snapshot extras: the dual-pane gate, tree and state, read like [DualPaneReconciler] does. */
    override fun extraFoldDiag(): String {
        val gate = resources.getBoolean(com.difft.android.R.bool.dual_pane_layout_active)
        val tree = DualPaneReconciler.treeIsDualPane(this)
        val rootPx = if (::binding.isInitialized) "${binding.root.width}x${binding.root.height}" else "none"
        return " gate=$gate tree=$tree dualPane=$isDualPaneMode started=$paneStarted paneRootPx=$rootPx"
    }

    private var insiderUpdateChecked = false

    private fun checkInsiderUpdate() {
        if (!environmentHelper.isInsiderChannel()) return
        val lastCheckUpdateTime = userManager.getUserData()?.lastCheckUpdateTime ?: 0
        if (!insiderUpdateChecked || (System.currentTimeMillis() - lastCheckUpdateTime > 30 * 60 * 1000)) {
            lifecycleScope.launch {
                delay(2000)
                updateManager.checkUpdate(this@IndexActivity, false)
            }
            insiderUpdateChecked = true
        }
    }

    private fun initFCMPush() {
        // 触发 FCM 初始化（PushUtil 内部使用独立 scope，不持有 Activity 引用）
        pushUtil.initFCMPush()
    }

    /** Last FcmInitResult logged; the StateFlow replays its value on every STARTED. */
    private var loggedFcmResult: FcmInitResult? = null

    private fun observeFcmInitResult() {
        pushUtil.fcmInitResult
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { result ->
                // The silent fallback start is idempotent and still runs on each replay; only the
                // warning is written once per result instance.
                val replay = result === loggedFcmResult
                loggedFcmResult = result
                when (result) {
                    is FcmInitResult.PlayServicesUnavailable -> {
                        if (!replay) L.w { "[Push][fcm] Google Play Services not available, status:${result.statusCode}" }
                        fcmFallbackStarter.onFcmUnavailable()
                    }

                    is FcmInitResult.Failure -> {
                        if (!replay) L.w { "[Push][fcm] FCM initialization failed: ${result.reason}" }
                        fcmFallbackStarter.onFcmUnavailable()
                    }

                    else -> {
                        // Idle, Loading, Success 都不需要处理
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        // 刷新截屏状态（从后台恢复时需要重新检查屏幕锁）
//        ScreenShotUtil.refreshWithPagePolicy(this, binding.indexViewpager.currentItem != 0)
        checkInsiderUpdate()
        reconcileCriticalAlertBypass()
    }

    private fun checkDisappearingMessage() {
        messageArchiveManager.startCheckTask()
        // Explicitly anchor server time at cold start; races the startup API wave harmlessly. Silent on failure.
        lifecycleScope.launch(Dispatchers.IO) {
            serverTimeSyncer.ensureAnchored()
        }
    }

    private fun startFileCleanupTask() {
        // Warm up the Glide cache master key off the main thread. First keystore key generation is a
        // slow synchronous binder call; resolving it here (before avatars render) keeps the main-thread
        // UI path (GlideCacheKeyManager.isCacheKeyReady) non-blocking and preserves cache hits.
        GlideCacheKeyManager.warmUp(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            FileUtil.clearDraftAttachmentsDirectory()
            FileUtil.deleteMessageAttachmentEmptyDirectories()
            // One-time purge of legacy plaintext media (re-encrypt to .encrypt, delete plaintext).
            LegacyPlaintextAttachmentMigration.runIfNeeded()
            // One-time removal of decrypted copies left beside a usable ciphertext (#1438).
            LegacyPlaintextAttachmentMigration.purgeRedundantPlaintextIfNeeded()
            AudioAmplitudesHelper.clearStaleTemps()
            // One-time purge of legacy plaintext avatar cache (delete; re-downloads encrypted, docs §15).
            LegacyPlaintextAvatarCleanup.runIfNeeded()
        }
        // One-time move of forwarded attachments to their per-copy directories (#1178). Owns its own
        // scope and startup delay, and outlives this activity, so it is not part of the block above.
        forwardAttachmentMigration.start()
    }

    /**
     * 清理空会话
     * 包括：基本空会话 + 超时的空会话（根据 activeConversation 配置）
     */
    private fun cleanEmptyRooms() {
        lifecycleScope.launch(Dispatchers.IO) {
            val activeConversationConfig = globalConfigsManager.getActiveConversationConfig()
            WCDBUpdateService.cleanEmptyRooms(activeConversationConfig)
        }
    }

    /**
     * 从 Intent 中提取 deeplink 数据并处理
     * 统一处理所有场景：通知点击、Push、Scheme URL
     * 
     * 这种方式比 Flow 机制更简单可靠：
     * - 不需要粘性事件/过滤机制
     */
    /**
     * Unified handler for Intent data.
     * Routes to deeplink handler or share handler based on action.
     */
    private fun handleIntentData(intent: Intent, isRecreation: Boolean = false) {
        testReturnGeneration++
        if (intent.hasCategory(NotificationTestReturnRoute.CATEGORY)) {
            handleNotificationTestReturn(intent)
            return
        }
        // Router rule 2: another Activity in a two-pane window asked for a conversation here.
        OpenConversationExtra.consume(intent, isRecreation)?.let { openExternalConversation(it) }
        when (intent.action) {
            Intent.ACTION_SEND -> handleShareIntent(intent)
            else -> handleDeeplinkFromIntent(intent, isRecreation)
        }
    }

    private fun handleNotificationTestReturn(intent: Intent) {
        val route = NotificationTestReturnRoute.read(intent) ?: run {
            L.w { "[NotificationTest] return_rejected reason=invalid_identity" }
            return
        }
        if (route.runId in handledTestReturns) return
        val generation = testReturnGeneration
        val requestVersion = navigator.requestVersion
        lifecycleScope.launch {
            val exists = notificationTestReturns.decide(route, navigator.retainedMePages()) is NotificationTestReturns.Decision.Existing
            if (!exists && !notificationTestReturns.discardStale(route)) {
                ToastUtil.show(com.difft.android.chat.R.string.notification_test_persistence_failed)
            }
            if (generation != testReturnGeneration || intent !== this@IndexActivity.intent) return@launch
            navigator.openMePage(
                keepExisting = { pages ->
                    notificationTestReturns.decide(route, pages) is NotificationTestReturns.Decision.Existing ||
                        pages.filterIsInstance<NotificationTestFragment>().any { it.hasActiveRun() }
                },
                createPage = { NotificationTestDestination.newFragment() },
                shouldOpen = { generation == testReturnGeneration && intent === this@IndexActivity.intent && route.runId !in handledTestReturns },
                expectedRequestVersion = requestVersion,
                onPresented = {
                    handledTestReturns.add(route.runId)
                    // Bound saved state even if a long-lived home handles many old notification taps.
                    if (handledTestReturns.size > 32) handledTestReturns.remove(handledTestReturns.first())
                },
            )
        }
    }

    /**
     * Handle deeplink data from Intent (notification click, push, scheme URL).
     * Priority: linkCategory > pushData > schemeUri
     */
    private fun handleDeeplinkFromIntent(intent: Intent, isRecreation: Boolean) {
        val linkCategory = intent.getIntExtra(LinkDataEntity.LINK_CATEGORY, -1)
        val pushData = intent.getStringExtra("pushData")
        val groupId = intent.getStringExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID)
        val contactId = intent.getStringExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID)
        val schemeUri = intent.data
        
        var linkDataEntity: LinkDataEntity? = null
        
        // Priority 1: Explicit link category (notification click, background settings, etc.)
        if (linkCategory != -1) {
            linkDataEntity = LinkDataEntity(linkCategory, groupId, contactId, null)
        }
        // Priority 2: Push data
        else if (!TextUtils.isEmpty(pushData)) {
            try {
                val pushCustomContent = gson.fromJson(
                    pushData,
                    com.difft.android.chat.data.PushCustomContent::class.java
                )
                linkDataEntity = LinkDataEntity(
                    category = LinkDataEntity.CATEGORY_PUSH,
                    gid = pushCustomContent.gid,
                    uid = pushCustomContent.uid,
                    uri = null
                )
            } catch (e: Exception) {
                L.e { "[IndexActivity] Error parsing pushData: ${e.message}" }
            }
        }
        // Priority 3: Scheme URL (chative://)
        else if (schemeUri != null) {
            val scheme = schemeUri.scheme
            if (scheme != null && AppScheme.allSchemes.contains(scheme)) {
                linkDataEntity = LinkDataEntity(LinkDataEntity.CATEGORY_SCHEME, null, null, schemeUri)
            }
        }
        
        // A recreation (language, theme) keeps the launch intent. Its chat was already opened, and
        // reopening it would pull the user back in (#1395). Other categories re-run as before: an
        // invite lookup or share started before the recreation does not survive it.
        val chatAlreadyOpened = isRecreation && linkDataEntity?.category.let {
            it == LinkDataEntity.CATEGORY_PUSH || it == LinkDataEntity.CATEGORY_MESSAGE
        }
        if (!chatAlreadyOpened) linkDataEntity?.let { handleDeeplink(it) }
    }

    /** Notification, link or router request: opens in the Chats tab's detail stack (both layouts). */
    private fun openExternalConversation(target: ConversationTarget) {
        navigator.openConversation(target, PaneNavigator.Origin.External)
    }

    private fun handleDeeplink(linkData: LinkDataEntity) {
        // Trigger screen lock check for deeplink scenario
        (application as com.difft.android.app.TempTalkApplication).triggerScreenLockCheck()

        when (linkData.category) {
            LinkDataEntity.CATEGORY_PUSH, LinkDataEntity.CATEGORY_MESSAGE -> {
                if (!TextUtils.isEmpty(linkData.gid)) {
                    if (ValidatorUtil.isGid(linkData.gid.toString())) {
                        openExternalConversation(ConversationTarget(ConversationTarget.Kind.GROUP, linkData.gid ?: ""))
                    } else {
                        L.e { "[Deeplink] CATEGORY_PUSH gid:${linkData.gid} is invalid" }
                    }
                } else if (!TextUtils.isEmpty(linkData.uid)) {
                    if (ValidatorUtil.isUid(linkData.uid.toString())) {
                        openExternalConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, linkData.uid ?: ""))
                    } else {
                        L.e { "[Deeplink] CATEGORY_PUSH uid:${linkData.uid} is invalid" }
                    }
                }
            }

            LinkDataEntity.CATEGORY_SCHEME -> {
                val uri = linkData.uri ?: return
                if (uri.scheme in AppScheme.allSchemes) {
                    if (uri.host?.equals("invite") == true) {
                        //chative://invite/?pi=QXCJ89dn
                        val pi = uri.getQueryParameter("pi")
                        if (!TextUtils.isEmpty(pi) && ValidatorUtil.isPi(pi.toString())) {
                            inviteUtils.queryByInviteCode(this, pi ?: "")
                        } else {
                            ToastUtil.showLong(R.string.invalid_link)
                        }
                    } else {
                        ToastUtil.showLong(R.string.invalid_link)
                    }
                } else if (uri.scheme?.equals("http") == true || uri.scheme?.equals("https") == true) {
                    val url = uri.toString()
                    SafeLinkOpener.open(this, url)
                } else {
                    ToastUtil.showLong(R.string.not_supported_link)
                }
            }

            LinkDataEntity.CATEGORY_BACKGROUND_CONNECTION_SETTINGS -> {
                BackgroundConnectionSettingsActivity.startActivity(this)
            }

            else -> {

            }
        }
    }

    override fun onStart() {
        super.onStart()
        paneStarted = true
        // Covers a window resize that happened while we were not on screen (a fold the OS
        // reported only as onConfigurationChanged, or none at all).
        paneReconciler.reconcile("onStart")
    }

    override fun onStop() {
        super.onStop()
        paneStarted = false
        staleMonitor.onStop()
    }

    // Before anything reads this activity's resources: Activity.attach() builds the window from them.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        staleOverridePanel = StaleLayoutOverride.applyIfActive(this, newBase)
    }

    override fun onHostDisplayChanged(display: Display) {
        if (::staleMonitor.isInitialized) staleMonitor.onHostDisplayChanged()
    }

    override fun onDestroy() {
        paneAnimator?.cancel()
        paneAnimator = null
        paneReconciler.detach()
        staleMonitor.detach()
        paneImeRouter?.detach()
        paneImeRouter = null
        super.onDestroy()
    }

    private fun setUserProfile() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loginRepo.setProfile()
                }
                L.d { "[IndexActivity] setUserProfile success" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[IndexActivity] setUserProfile error: ${e.stackTraceToString()}" }
            }
        }
    }

    @Inject
    lateinit var notificationChannelManager: NotificationChannelManager

    /** Re-applies bypass-Do-Not-Disturb on the critical-alert category on each return to foreground. */
    private fun reconcileCriticalAlertBypass() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                notificationChannelManager.reconcileCriticalAlertBypass()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[IndexActivity] critical alert bypass reconcile failed error=${e.javaClass.simpleName}" }
            }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            try {
                val uri = getUriFromIntent(intent)

                if (uri != null) {
                    // 有URI，作为文件处理（包括txt文件、图片、PDF等）
                    handleSharedFileUri(uri)
                } else {
                    // 无URI，作为纯文本处理
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (sharedText != null) {
                        selectChatsUtils.showChatSelectAndSendDialog(
                            this@IndexActivity,
                            sharedText,
                        )
                    } else {
                        // 既没有URI也没有文本，记录异常情况
                        L.w { "[Share] Received share intent but neither URI nor text found. Intent type: ${intent.type}" }
                    }
                }
            } catch (e: Exception) {
                L.e { "SharedContent Received Exception: ${e.stackTraceToString()}" }
            }
        }
    }

    /**
     * 处理分享的文件URI
     */
    private fun handleSharedFileUri(uri: Uri) {
        lifecycleScope.launch {
            val rejected = withContext(Dispatchers.IO) {
                SharedUriGuard.pointsIntoAppStorage(uri, SharedUriGuard.appStorageRoots(this@IndexActivity))
            }
            if (rejected) {
                L.w { "[Share] rejected file uri into app storage" }
                ToastUtil.showLong(R.string.unsupported_file_type)
                return@launch
            }

            // 优先判断文件大小是否超过200MB
            val fileSize = withContext(Dispatchers.IO) {
                FileUtil.getFileSize(uri)
            }

            if (fileSize >= FileUtil.MAX_SUPPORT_FILE_SIZE) {
                ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                return@launch
            }

            val copied = withContext(Dispatchers.IO) { runCatching { copyUriToFile(uri) } }
            val file = copied.getOrNull()
            if (file == null) {
                val error = copied.exceptionOrNull()
                if (error is SizeLimitExceededException) {
                    // The provider reported a size under the limit but streamed more.
                    L.w { "[Share] shared stream exceeded size limit reported=$fileSize" }
                    ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                } else {
                    L.e { "[Share] copy shared file failed: ${error?.stackTraceToString()}" }
                    ToastUtil.showLong(R.string.unsupported_file_type)
                }
                return@launch
            }

            selectChatsUtils.showChatSelectAndSendDialog(
                this@IndexActivity,
                "",
                file = file
            )
        }
    }

    private fun getUriFromIntent(intent: Intent): Uri? {
        // Try to get URI from ClipData first (more reliable)
        intent.clipData?.let { clipData ->
            if (clipData.itemCount > 0) {
                val uri = clipData.getItemAt(0).uri
                L.d { "[IndexActivity] Got URI from ClipData: $uri" }
                return uri
            }
        }

        // Fallback to EXTRA_STREAM
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }

        L.d { "[IndexActivity] Got URI from EXTRA_STREAM: $uri" }
        return uri
    }

    /**
     * 将 URI 复制到本地文件
     * 注意：此方法涉及文件 IO 操作，应在子线程（如 Dispatchers.IO）中调用
     */
    private fun copyUriToFile(uri: Uri): File {
        val mimeType = contentResolver.getType(uri) // Get the MIME type
        val extension = mimeType?.let {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(it) // Get file extension from MIME type
        } ?: "tmp" // Default to a .tmp extension if MIME type is unknown

        val fileName = "shared_file_${System.currentTimeMillis()}.$extension"
        // Staged in internal storage, not draft_blobs: the copy happens before the user picks a chat,
        // and draft_blobs may sit on external storage other apps can read (#1429). sendFile copies it
        // into attachment storage, and the startup sweep reclaims whatever a cancel or crash left behind.
        val file = File(FilePathManager.shareImportDir, fileName)

        // Copy the bytes, capturing the exact failure so "unsupported file type" reports have a root
        // cause in logcat. Try openInputStream first, then fall back to openFileDescriptor — some
        // providers (e.g. our decrypting EncryptedAttachmentProvider, OEM file managers) succeed on
        // one path but not the other.
        val copied = try {
            copyUriContent(uri, file)
        } catch (e: SizeLimitExceededException) {
            file.delete()
            throw e
        }
        if (!copied) {
            file.delete()
            throw java.io.FileNotFoundException("[IndexActivity] no readable stream for shared uri=${uri.redactedForLog()} (mime=$mimeType)")
        }

        return file
    }

    /**
     * The sender controls both the reported size and the stream, so the copy itself enforces the
     * limit; [SizeLimitExceededException] propagates instead of falling back to the next path.
     */
    private fun copyUriContent(uri: Uri, target: File): Boolean {
        val maxBytes = FileUtil.MAX_SUPPORT_FILE_SIZE - 1L
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyToBounded(it, maxBytes) }
                return true
            }
            L.w { "[IndexActivity] openInputStream returned null for ${uri.redactedForLog()}" }
        } catch (e: SizeLimitExceededException) {
            throw e
        } catch (e: Exception) {
            L.w { "[IndexActivity] openInputStream failed for ${uri.redactedForLog()}: ${e.stackTraceToString()}" }
        }

        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    target.outputStream().use { input.copyToBounded(it, maxBytes) }
                }
                return true
            }
        } catch (e: SizeLimitExceededException) {
            throw e
        } catch (e: Exception) {
            L.e { "[IndexActivity] openFileDescriptor failed for ${uri.redactedForLog()}: ${e.stackTraceToString()}" }
        }

        return false
    }

    /**
     * Redacts a shared uri for logging: keep only scheme + authority (the provider identity, needed to
     * diagnose "unsupported file type" reports). The path/query segments are dropped because they may
     * carry a full attachment filename (our `content://<pkg>.encryptedattachment/m/<id>/<name>`) or
     * another app's identifiers — both blacklisted by the logging standard for persisted logs.
     */
    private fun Uri.redactedForLog(): String = "$scheme://$authority/…"

    private fun initWCDB() {
        lifecycleScope.launch(Dispatchers.IO) {
            wcdb.tablesMap
        }
    }

    private fun fetchCallServiceUrlAndCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (NetworkUtils.isNetworkAvailable(this@IndexActivity)) {
                LCallManager.fetchCallServiceUrlAndCache()
            }
        }
    }

    private fun fetchFeatureGrayConfigs() {
        lifecycleScope.launch(Dispatchers.IO) {
            FeatureGrayManager.init()
        }
    }

    // ==================== Dual-pane layout support ====================

    /**
     * Setup dual-pane layout for large screens (width >= 673dp AND height >= 480dp)
     * Layout qualifier w673dp-h480dp ensures this layout only loads when both conditions are met.
     * Detects dual-pane mode by checking for detail_pane view which only exists in that layout.
     */
    private fun setupDualPaneLayout() {
        // Same configuration as the inflation that just ran, so the mirror agrees with the tree
        // (PaneGateResourceAgreementTest).
        isDualPaneMode = resources.getBoolean(com.difft.android.R.bool.dual_pane_layout_active)

        if (isDualPaneMode) {
            // The navigator paints the root as list / detail columns; redraw them and the
            // list/detail line whenever the panes move.
            binding.root.viewTreeObserver.addOnGlobalLayoutListener {
                (binding.root.background as? PaneSplitBackgroundDrawable)?.onPanesLaidOut()
                syncListDetailDivider()
            }

            // Apply list pane width based on current text size (avoid flicker on cold start)
            applyListPaneWidth(TextSizeUtil.isLarger)
            // Re-apply once the edge-to-edge insets have landed: the cold-start apply above
            // runs before the first inset dispatch, when root padding is still 0.
            binding.root.post { applyListPaneWidth(TextSizeUtil.isLarger) }
        }
    }

    /**
     * Adjust the list pane width.
     *
     * Priority order:
     *   1. User-dragged ratio ([DualPaneRatioUtil.hasUserOverride]) — apply saved ratio to current
     *      available width, clamped to per-pane minimums.
     *   2. Larger text mode — 50/50 split so contact / group names have room at the bigger font.
     *   3. Default — the banded `dual_pane_list_default_width` dimen (280dp compact band,
     *      360dp in the w900dp tablet band).
     *
     * All branches clamp to the [paneBudget] minimums so neither pane collapses below usable
     * size; the detail floor keeps the 270dp-wide voice / contact / attach message bubbles
     * unclipped wherever the window can afford it (see values/dimens.xml).
     *
     * Uses [WindowSizeClassUtil.getWindowWidthPx] (Jetpack WindowMetrics) instead of
     * [android.content.res.Configuration.screenWidthDp]: on foldables during fold/rotate
     * transitions, Configuration can report device-level dimensions while the actual window
     * is smaller. Conversion uses the Activity's own density to avoid the
     * Application-vs-Activity density mismatch that affects [com.difft.android.base.utils.dp]
     * on multi-display foldables — see anti-pattern #48.
     */
    private fun applyListPaneWidth(isLarger: Boolean) {
        if (!isDualPaneMode) return
        val listPane = findViewById<View>(com.difft.android.R.id.list_pane) ?: return
        // While a collapse/expand animation is driving the width, or the pane is collapsed,
        // the animator / collapsed constant owns it (large-font 50/50 and saved ratio yield).
        if (paneAnimator?.isRunning == true) return
        if (DualPaneCollapseUtil.isCollapsed) {
            if (listPane.layoutParams.width != COLLAPSED_LIST_WIDTH_PX) {
                listPane.layoutParams = listPane.layoutParams.apply { width = COLLAPSED_LIST_WIDTH_PX }
            }
            applyCollapsedContentImportance()
            return
        }

        val targetWidth = resolveListTargetPx(isLarger)
        if (listPane.layoutParams.width != targetWidth) {
            listPane.layoutParams = listPane.layoutParams.apply { width = targetWidth }
        }
    }

    /**
     * The expanded list-pane width for the current window: saved drag ratio, else large-font
     * 50/50, else the band default — clamped so the detail pane keeps its floor. Shared by
     * [applyListPaneWidth] (static apply) and [expandListPane] (animation target).
     */
    private fun resolveListTargetPx(isLarger: Boolean): Int {
        val available = availablePaneSpacePx()
        val budget = paneBudget()
        // Hard upper bound: never place the list where the detail pane would drop below the
        // 360dp floor; where 360 does not fit, the max collapses to the list minimum.
        val listMaxPx = (available - budget.dragFloorPx).coerceAtLeast(budget.listMinPx)

        val rawTarget = when {
            DualPaneRatioUtil.hasUserOverride ->
                (available * DualPaneRatioUtil.currentRatio).toInt()
            isLarger ->
                available / 2
            else ->
                budget.listDefaultPx
        }
        return rawTarget.coerceIn(budget.listMinPx, listMaxPx)
    }

    /**
     * Wire up the draggable divider once dual-pane mode is active.
     * - ACTION_MOVE: adjust [listPane] width live, clamped to per-pane minimums.
     * - ACTION_UP: persist the resulting ratio (so subsequent rotate / fold / large-font toggle
     *   preserve the user's preference) and trigger a one-shot rebind on the detail pane's
     *   message RecyclerView so existing bubble widths refresh from the new RecyclerView size.
     *
     * Collapse gesture: there is no stable list width below the minimum. Pulling a further
     * [COLLAPSE_SNAP_TRIGGER_DP] past it snaps the list pane closed (detail full width);
     * from the collapsed state, pulling the handle back out by the same distance — or
     * tapping it, tapping a rail tab (while the rail is visible), or pressing back — snaps it
     * open again.
     */
    private fun setupDualPaneDivider() {
        if (!isDualPaneMode) return
        val divider = findViewById<DraggableDividerView>(com.difft.android.R.id.divider_pane) ?: return
        val listPane = findViewById<View>(com.difft.android.R.id.list_pane) ?: return

        // Virtual (unclamped) finger position for the active gesture, in list-width px;
        // null between gestures. Lets the finger travel past the minimum while the pane
        // rubber-bands, and lets the release settle by position + velocity — the same model
        // as the platform pane-expansion handles.
        var virtualWidthPx: Int? = null

        divider.onDrag = { rawDelta, isEnd, rawVelocityX, cancelled ->
            // Mirror the gesture in RTL: the panes mirror with start/end constraints, so the
            // list grows LEFTWARD there — a leftward pull widens it and the collapse/expand
            // directions flip with it. Raw values are screen-space (positive = rightward).
            val rtl = listPane.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val delta = if (rtl) -rawDelta else rawDelta
            val velocityX = if (rtl) -rawVelocityX else rawVelocityX
            when {
                // A snap animation owns the width; swallow the tail of the gesture.
                paneAnimator?.isRunning == true -> virtualWidthPx = null

                DualPaneCollapseUtil.isCollapsed -> {
                    val overshoot = ((virtualWidthPx ?: 0) + delta).coerceAtLeast(0)
                    if (isEnd) {
                        virtualWidthPx = null
                        // A system-cancelled gesture rolls back; only a real release commits.
                        if (!cancelled && (overshoot >= snapTriggerPx() || velocityX >= flingVelocityPx())) {
                            divider.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            expandListPane()
                        } else {
                            // Retract the peek; keep the peeked content at its laid-out width.
                            animateListPaneWidth(COLLAPSED_LIST_WIDTH_PX, frozenContentPx = binding.listContent.layoutParams.width)
                        }
                    } else {
                        virtualWidthPx = overshoot
                        // Peek: reveal the edge of the (fully laid-out) list as feedback.
                        if (binding.listContent.layoutParams.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                            binding.listContent.layoutParams = binding.listContent.layoutParams.apply {
                                width = resolveListTargetPx(TextSizeUtil.isLarger)
                            }
                        }
                        val peek = COLLAPSED_LIST_WIDTH_PX + rubberPx(overshoot)
                        if (listPane.layoutParams.width != peek) {
                            listPane.layoutParams = listPane.layoutParams.apply { width = peek }
                        }
                    }
                }

                else -> {
                    val available = availablePaneSpacePx()
                    val budget = paneBudget()
                    // Drag clamps against the 360dp drag floor, never the static fallback:
                    // where 360 does not fit the range collapses and the divider is inert.
                    val listMaxPx = (available - budget.dragFloorPx).coerceAtLeast(budget.listMinPx)

                    val virtual = ((virtualWidthPx ?: listPane.layoutParams.width) + delta)
                        .coerceAtMost(listMaxPx)
                    val overshoot = (budget.listMinPx - virtual).coerceAtLeast(0)
                    // In range: 1:1. Past the minimum: rubber-band so the finger keeps getting
                    // feedback instead of hitting a dead stop.
                    val displayWidth = if (overshoot == 0) virtual else budget.listMinPx - rubberPx(overshoot)

                    if (isEnd) {
                        virtualWidthPx = null
                        // The gesture always wins — even over the empty detail state (the edge
                        // handle, tab taps and back all lead out of it). Only SYSTEM transitions
                        // that empty the pane (conversation removed, cold start with nothing to
                        // restore) auto-expand, so an invisible sometimes-it-works rule never
                        // reads as a bug.
                        val commitCollapse = !cancelled &&
                            (overshoot >= snapTriggerPx() || velocityX <= -flingVelocityPx())
                        when {
                            commitCollapse -> {
                                divider.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                                collapseListPane()
                            }
                            overshoot > 0 -> {
                                // Not committed: spring back to the minimum and persist it.
                                animateListPaneWidth(budget.listMinPx)
                                if (available > 0) DualPaneRatioUtil.updateRatio(budget.listMinPx.toFloat() / available)
                            }
                            else -> {
                                if (available > 0) {
                                    DualPaneRatioUtil.updateRatio(displayWidth.toFloat() / available)
                                    refreshDetailPaneMessageBubbles()
                                }
                            }
                        }
                    } else {
                        virtualWidthPx = virtual
                        if (listPane.layoutParams.width != displayWidth) {
                            listPane.layoutParams = listPane.layoutParams.apply { width = displayWidth }
                        }
                    }
                }
            }
        }

        // Tap on the collapsed handle re-opens the list (also TalkBack's activate action).
        divider.setOnClickListener {
            if (DualPaneCollapseUtil.isCollapsed) expandListPane()
        }

        updateDividerAccessibility(divider)
    }

    /** Collapse the list pane so the detail pane takes the full width (Back re-opens it). */
    private fun collapseListPane() {
        if (DualPaneCollapseUtil.isCollapsed) return
        DualPaneCollapseUtil.setCollapsed(true)
        animateListPaneWidth(COLLAPSED_LIST_WIDTH_PX)
        findViewById<DraggableDividerView>(com.difft.android.R.id.divider_pane)
            ?.let { updateDividerAccessibility(it) }
    }

    /** Expand the list pane back to its saved-ratio / default width. */
    private fun expandListPane(animate: Boolean = true) {
        if (!DualPaneCollapseUtil.isCollapsed) return
        DualPaneCollapseUtil.setCollapsed(false)
        applyCollapsedContentImportance()
        if (animate) {
            animateListPaneWidth(resolveListTargetPx(TextSizeUtil.isLarger))
        } else {
            applyListPaneWidth(TextSizeUtil.isLarger)
        }
        findViewById<DraggableDividerView>(com.difft.android.R.id.divider_pane)
            ?.let { updateDividerAccessibility(it) }
    }

    /**
     * Animate list_pane between the collapsed constant and an expanded target.
     *
     * The pane content ([ActivityIndexBinding.listContent]: the pager plus the floating tab
     * capsule, start-aligned) is frozen at the wider endpoint for the duration, so the list is
     * revealed/concealed by the pane's clip instead of its rows re-laying out on every frame
     * (widths inside 1..min are never a layout state the rows see) and the capsule keeps its
     * place instead of re-centring in the shrinking pane; restored to MATCH_PARENT when the
     * animation lands.
     */
    private fun animateListPaneWidth(targetPx: Int, frozenContentPx: Int = 0) {
        val listPane = findViewById<View>(com.difft.android.R.id.list_pane) ?: return
        val content = binding.listContent
        // Cancel BEFORE freezing: cancel() fires the old animator's doOnEnd synchronously,
        // which restores MATCH_PARENT — done after the freeze it would defeat it.
        paneAnimator?.cancel()
        paneAnimator = null

        val startPx = listPane.layoutParams.width
        if (startPx == targetPx) {
            // Nothing to animate, but a peek may have left the content frozen — restore it,
            // and keep the drag-end bubble-refresh contract that doOnEnd would have honoured.
            if (content.layoutParams.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                content.layoutParams = content.layoutParams.apply {
                    width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
            applyCollapsedContentImportance()
            refreshDetailPaneMessageBubbles()
            return
        }
        content.layoutParams = content.layoutParams.apply {
            width = if (frozenContentPx > 0) frozenContentPx else maxOf(startPx, targetPx)
        }

        paneAnimator = ValueAnimator.ofInt(startPx, targetPx).apply {
            duration = PANE_SNAP_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                listPane.layoutParams = listPane.layoutParams.apply { width = anim.animatedValue as Int }
            }
            doOnEnd {
                content.layoutParams = content.layoutParams.apply {
                    width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
                applyCollapsedContentImportance()
                refreshDetailPaneMessageBubbles()
            }
            start()
        }
    }

    /**
     * Keep the collapsed 1px list pane out of the accessibility tree and the keyboard focus
     * order: its rows are still laid out and would otherwise be reachable invisibly by
     * TalkBack swipes and hardware TAB. Restored the moment the pane is expanded (or starts
     * peeking, so the revealed content reads normally).
     */
    private fun applyCollapsedContentImportance() {
        val collapsed = DualPaneCollapseUtil.isCollapsed
        binding.listContent.importantForAccessibility = if (collapsed) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        binding.listContent.descendantFocusability = if (collapsed) {
            android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    private fun snapTriggerPx(): Int =
        (COLLAPSE_SNAP_TRIGGER_DP * resources.displayMetrics.density).toInt()

    private fun flingVelocityPx(): Float =
        COLLAPSE_FLING_VELOCITY_DP_S * resources.displayMetrics.density

    /** Damped overshoot: 1/3 rate, capped — the standard overscroll rubber-band feel. */
    private fun rubberPx(overshootPx: Int): Int =
        minOf(overshootPx / 3, (RUBBER_BAND_MAX_DP * resources.displayMetrics.density).toInt())

    private fun updateDividerAccessibility(divider: DraggableDividerView) {
        divider.contentDescription = getString(
            if (DualPaneCollapseUtil.isCollapsed) {
                com.difft.android.R.string.dual_pane_list_expand_hint
            } else {
                com.difft.android.R.string.dual_pane_divider_drag_hint
            }
        )
    }

    /**
     * Tell the active ChatFragment's message RecyclerView to rebind its visible items.
     * This refreshes containerWidth-dependent calculations after the detail pane resizes.
     *
     * MVP: uses notifyDataSetChanged on visible items. Heavier than payload-based refresh
     * but acceptable for the once-per-drag-end frequency.
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun refreshDetailPaneMessageBubbles() {
        val detailPane = findViewById<View>(com.difft.android.R.id.detail_pane) ?: return
        // Walk descendants to find any RecyclerView. ChatFragment hosts its message list as one.
        findFirstRecyclerView(detailPane)?.adapter?.notifyDataSetChanged()
    }

    private fun findFirstRecyclerView(root: View): androidx.recyclerview.widget.RecyclerView? {
        if (root is androidx.recyclerview.widget.RecyclerView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstRecyclerView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun availablePaneSpacePx(): Int {
        // The list and detail panes share the whole root width: the list/detail line and
        // divider_pane are floating overlays (the latter with a negative marginStart) and do
        // NOT consume horizontal layout space, and the home tabs float over the list pane.
        // WindowMetrics bounds INCLUDE system decoration, but the panes lay out inside the
        // root that BaseActivity's edge-to-edge pass pads by the horizontal insets — without
        // subtracting that padding the detail-pane floor is overstated by the inset (a side
        // navigation bar or landscape cutout) and the drag clamp can starve it below 360dp.
        val insetPaddingPx = binding.root.paddingLeft + binding.root.paddingRight
        return (WindowSizeClassUtil.getWindowWidthPx(this) - insetPaddingPx).coerceAtLeast(0)
    }

    /** Pane minimums resolved from the banded dual_pane_* dimens for one apply/drag pass. */
    private data class PaneBudget(
        val listMinPx: Int,
        val listDefaultPx: Int,
        val dragFloorPx: Int
    )

    /**
     * Resolve the pane budget (window minus rail and divider is the caller's `available`).
     *
     * ONE floor governs both static placement and the interactive clamp:
     * `dual_pane_detail_drag_min_width` (360dp — keeps the 270dp fixed-width bubbles
     * unclipped, see values/dimens.xml). On windows where 360 does not fit next to the list
     * minimum (below 713dp) the list max collapses to the list minimum, the divider goes
     * inert in-range, and the detail pane simply receives the remainder (320-360dp there by
     * the 673 gate invariant) — no clamp may ever PUSH it below 360, including a persisted
     * ratio re-applied after crossing a size boundary.
     */
    private fun paneBudget(): PaneBudget = PaneBudget(
        listMinPx = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_list_min_width),
        listDefaultPx = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_list_default_width),
        dragFloorPx = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_detail_drag_min_width)
    )

    private companion object {
        // 1px, not 0: a 0 width degrades to ConstraintLayout's MATCH_CONSTRAINT and the
        // "collapsed" pane silently takes over the row instead of disappearing.
        const val COLLAPSED_LIST_WIDTH_PX = 1

        // Finger travel past the list minimum (or out of the collapsed state) that commits
        // the snap on release — hysteresis so jitter at the clamp edge cannot toggle the pane.
        const val COLLAPSE_SNAP_TRIGGER_DP = 48

        // A release with at least this outward horizontal velocity commits the snap
        // regardless of distance (fling-to-collapse / fling-to-expand).
        const val COLLAPSE_FLING_VELOCITY_DP_S = 1000

        // Cap on the visible rubber-band displacement past the minimum.
        const val RUBBER_BAND_MAX_DP = 32

        const val PANE_SNAP_ANIM_MS = 220L

        // Floating home chrome: bottom gap matching iOS's 28, and the clearance kept above a
        // 3-button navigation bar.
        const val HOME_TAB_BAR_BOTTOM_GAP_DP = 28
        const val HOME_TAB_BAR_NAV_CLEARANCE_DP = 4
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navigator.saveState(outState)
        listLayer.saveState(outState)
        outState.putStringArrayList("handled_notification_test_returns", ArrayList(handledTestReturns))
    }

    private val paneListener = object : PaneNavigator.Listener {
        override fun onDetailStateChanged() {
            if (::startupPromptHost.isInitialized) startupPromptHost.onDetailPaneChanged()
            notifyListFragmentSelectionChanged()
        }

        // Only system transitions that empty the pane auto-expand; a user collapse over a
        // non-empty pane stays.
        override fun onCurrentDetailEmptied() {
            expandListPane()
        }

        // A requested tab switch leaves the list-pane layer like a rail tap, so it never covers another tab's list.
        override fun onSelectTabRequested(tab: Int) {
            if (::listLayer.isInitialized) listLayer.close()
            binding.indexViewpager.setCurrentItem(tab, false)
        }
    }

    /**
     * Notify list fragments about selection change.
     * Finds ViewPager2 fragments and forwards the current selection.
     */
    private fun notifyListFragmentSelectionChanged() {
        if (!isDualPaneMode) return
        val selectedId = navigator.currentSelectedId
        supportFragmentManager.fragments.forEach { fragment ->
            (fragment as? DualPaneSelectionListener)?.updateDualPaneSelection(selectedId)
        }
    }

    // ==================== ConversationNavigationCallback implementation ====================

    override fun onOneOnOneConversationSelected(contactId: String, jumpMessageTimestamp: Long?) {
        openConversationFromCurrentTab(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, contactId, jumpMessageTimestamp))
    }

    override fun onGroupConversationSelected(groupId: String, jumpMessageTimestamp: Long?) {
        openConversationFromCurrentTab(ConversationTarget(ConversationTarget.Kind.GROUP, groupId, jumpMessageTimestamp))
    }

    // Both layouts: one-pane opens the conversation as the full-window detail layer.
    private fun openConversationFromCurrentTab(target: ConversationTarget) {
        navigator.openConversation(target, PaneNavigator.Origin.Tab(navigator.currentTab))
    }

    override fun onContactDetailSelected(contactId: String) {
        if (isDualPaneMode) {
            navigator.replaceRoot(navigator.currentTab, IndexDetailPages.contactDetail(contactId), selectionId = contactId)
        } else {
            // Single pane: the contact card is the same half-height sheet the chat screen uses.
            ContactDetailBottomSheetDialogFragment.show(this, contactId)
        }
    }

    override fun openConversation(target: ConversationTarget) {
        openConversationFromCurrentTab(target)
    }

    override fun currentConversationOnScreen(): String? =
        if (::navigator.isInitialized) navigator.currentConversationOnScreen() else null

    override val detailCoversList: StateFlow<Boolean>
        get() = if (::listCovered.isInitialized) listCovered else navigator.detailCoversList

    // ==================== ListPaneLayerOpener implementation ====================

    /** The list's search entry: search in the list pane (two-pane) or full-window (one-pane). */
    override fun openSearchLayer(): Boolean {
        listLayer.open(ListPaneLayerRoot.SEARCH)
        return true
    }

    /** The chat list's folded-groups row: the folded list in the list pane (two-pane) or full-window (one-pane). */
    override fun openFoldedGroupsLayer(): Boolean {
        listLayer.open(ListPaneLayerRoot.FOLDED_GROUPS)
        return true
    }

    // ==================== DualPaneHost implementation ====================

    override val homeTabBarObstructionPx: StateFlow<Int>
        get() = homeTabBarObstruction

    /**
     * MeFragment's settings and profile pages: the root of the Me tab's detail stack (replacing
     * the previous kind or keeping a matching root). Both layouts use the same stack.
     */
    override fun showDetailFragment(fragment: Fragment, tag: String?) {
        navigator.showMePage(fragment)
    }

    /**
     * The message list of the current tab's conversation, in either layout (confidential viewers
     * restored with the Activity ask from their onContentViewCreated, before ViewPager2 restores
     * its page, so this reads the navigator's restored tab and its host directly).
     */
    override fun getChatMessageListFragment(): ChatMessageListFragment? =
        when (val root = if (::navigator.isInitialized) navigator.currentConversationRoot() else null) {
            is ChatFragment -> root.getChatMessageListFragment()
            is GroupChatFragment -> root.getChatMessageListFragment()
            else -> null
        }

    // ==================== ChatInputFocusable implementation ====================

    override fun focusCurrentChatInputIfMatches(conversationId: String): Boolean {
        if (!::navigator.isInitialized || navigator.currentConversationOnScreen() != conversationId) return false
        (navigator.currentConversationRoot() as? ChatFragment)?.focusInputAndShowKeyboard()
        return true
    }
}
