package com.difft.android

import com.difft.android.base.ui.topchrome.TopChromeHost
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.difft.android.chat.contacts.contactsdetail.ContactCardHost
import com.difft.android.chat.contacts.contactsdetail.ContactCardRequest
import com.difft.android.chat.contacts.groupcard.GroupCardNavigationCallback
import com.difft.android.chat.ui.BubbleWidth
import com.difft.android.chat.ui.prewarmChatWallpaper
import com.difft.android.chat.ui.theme.ChatThemeSetting
import com.difft.android.setting.notificationtest.NotificationTestReturnRoute
import com.difft.android.setting.notificationtest.NotificationTestReturns
import com.difft.android.setting.notificationtest.NotificationTestFragment
import com.difft.android.setting.notificationtest.NotificationTestDestination
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
import com.difft.android.base.ui.glass.GlassBackdrop
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
import com.difft.android.chat.group.create.CreateGroupFragment
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
import com.difft.android.home.HomeGlassBackdrop
import com.difft.android.home.HomeTabCapsule
import com.difft.android.home.HomeTabCapsuleState
import com.difft.android.home.HomeSideChrome
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.base.ui.theme.DifftTheme
import kotlinx.coroutines.flow.update
import com.difft.android.dualpane.StaleLayoutMonitor
import com.difft.android.dualpane.StaleLayoutOverride
import com.difft.android.dualpane.shownDetailStackTop
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
import java.io.InputStream
import javax.inject.Inject


@AndroidEntryPoint
class IndexActivity :
    BaseActivity(),
    ConversationNavigationCallback,
    ContactCardHost,
    GroupCardNavigationCallback,
    ChatMessageListProvider,
    ChatInputFocusable,
    PaneNavigator.Owner,
    ListPaneLayer.Owner,
    ListPaneLayerOpener {
    private lateinit var binding: ActivityIndexBinding

    
    
    override var isDualPaneMode = false
        private set
    override val currentSelectedConversationId: String?
        get() = if (::navigator.isInitialized) navigator.currentSelectedId else null

    
    
    private lateinit var navigator: PaneNavigator
    override val paneNavigator: PaneNavigator get() = navigator

    
    override lateinit var listLayer: ListPaneLayer
        private set

    
    private lateinit var listCovered: StateFlow<Boolean>

    
    private var paneImeRouter: PaneImeRouter? = null

    
    private var hierarchyStateRestored = false

    
    private var paneAnimator: ValueAnimator? = null

    
    
    
    private lateinit var paneReconciler: DualPaneReconciler
    private var paneStarted = false

    
    private val homeTabState = MutableStateFlow(HomeTabCapsuleState())
    private var homeGlassBackdrop: GlassBackdrop? = null

    
    
    private lateinit var staleMonitor: StaleLayoutMonitor
    private var staleOverridePanel: String? = null

    
    
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

        setupDualPaneLayout()
        if (isDualPaneMode) paneImeRouter = PaneImeRouter(binding.root).also { it.attach() }
        navigator = PaneNavigator(this, isDualPaneMode, IndexDetailPages, paneListener)
        navigator.attach(savedInstanceState)
        listLayer = ListPaneLayer(this, navigator.chrome, IndexDetailPages, navigator.detailCoversList) {
            if (::startupPromptHost.isInitialized) startupPromptHost.onDetailPaneChanged()
        }
        listLayer.attach(savedInstanceState)
        listCovered = combine(navigator.detailCoversList, listLayer.coversList) { detailLayer, listPaneLayer -> detailLayer || listPaneLayer }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, navigator.detailCoversList.value || listLayer.coversList.value)
        paneReconciler = DualPaneReconciler(this, binding.root, isStarted = { paneStarted })
        paneReconciler.attach()
        staleMonitor = StaleLayoutMonitor(this, paneReconciler, isStarted = { paneStarted }, activeOverridePanel = staleOverridePanel)

        mountHomeTabCapsule()
        sideChrome?.let { chrome ->
            HomeSideChrome.attach(
                owner = this, chrome = chrome, tabState = homeTabState,
                onTabClick = ::onHomeTabClick, onChatsDoubleTap = RecentChatUtil::emitChatDoubleTab, onSearch = ::onHomeSearchClick,
                onSearchFrom = { searchButtonBounds = it },
                listCovered = listCovered, bottomBar = binding.homeTabBar, bottomBarObstruction = homeTabBarObstruction,
                dualPaneMinWidthDp = ::dualPaneMinWidthDp,
                onReserveChanged = {
                    if (isDualPaneMode) applyListPaneWidth(TextSizeUtil.isLarger)
                },
            )
        }

        
        
        val density = resources.displayMetrics.density
        val swDp = resources.configuration.smallestScreenWidthDp
        val widthDp = (WindowSizeClassUtil.getWindowWidthPx(this) / density).toInt()
        val heightDp = (WindowSizeClassUtil.getWindowHeightPx(this) / density).toInt()
        val dualPane = isDualPaneMode
        L.i { "[IndexActivity] screen swDp=$swDp widthDp=$widthDp heightDp=$heightDp dualPane=$dualPane" }

        
        TextSizeUtil.loadAndEmitTextSize()
        ChatThemeSetting.loadAndEmit()
        prewarmChatWallpaper(this)
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
        
        
        if (savedInstanceState == null && isDualPaneMode) expandListPane(animate = false)

        setupViewPager(restoring = savedInstanceState != null)
        observeMyContactForHomeTab()

        handleIntentData(intent, isRecreation = savedInstanceState != null)
        recordUA()

        initWCDB()

        
        
        
        lifecycleScope.launch(Dispatchers.IO) { weakContactReconciler.reconcile("coldStart") }

        startReceivingMessages()

        processPendingAndFailedMessages()

        WCDBUpdateService.start()

        cleanEmptyRooms()

        initFCMPush()

        observeFcmInitResult()

        updateManager.attach(this)

        
        
        startupPromptHost.attach(
            activity = this,
            updateManager = updateManager,
            lockPending = (application as TempTalkApplication).screenLockPendingState,
            
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

        syncConversationAndFocusState()

        
        
        translateModelStore.syncOnStart()

        
        
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
            if (isDualPaneMode) {
                overScrollMode = View.OVER_SCROLL_NEVER
                (getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            }
            adapter = object : FragmentStateAdapter(this@IndexActivity) {
                private val fragmentClasses: List<Class<out Fragment>> =
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

            
            
            if (restoring) setCurrentItem(navigator.currentTab, false)

            registerOnPageChangeCallback(object : OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    selectIndicator(position)
                    
                    
                    navigator.selectTab(position)
                    if (position == PaneNavigator.TAB_CONTACTS) {
                        lifecycleScope.launch(Dispatchers.IO) { groupUtil.syncGroupListIfStale() }
                    }
                }
            }.also {
                
                it.onPageSelected(currentItem)
            })

        }
    }

    
    private var lastLoggedBadgeState: List<Int>? = null

    private fun observeAndUpdateUnreadMessageCountBadge() {
        recentChatViewModel.allRecentRoomsStateFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { rooms ->
                
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

    





    private fun mountHomeTabCapsule() {
        
        homeGlassBackdrop = HomeGlassBackdrop(binding.listContent, binding.indexViewpager)
        
        
        
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
            
            DifftTheme(applyWindowBackground = false) {
                val state by homeTabState.collectAsStateWithLifecycle()
                HomeTabCapsule(
                    state = state,
                    onTabClick = ::onHomeTabClick,
                    onChatsDoubleTap = RecentChatUtil::emitChatDoubleTab,
                    onSearch = ::onHomeSearchClick,
                    onSearchFrom = { searchButtonBounds = it },
                )
            }
        }
        binding.homeTabBar.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            homeTabBarObstruction.value = if (v.visibility == View.VISIBLE) bottom - top else 0
        }
    }

    
    private var searchButtonBounds: android.graphics.Rect? = null

    
    private fun onHomeSearchClick() {
        var origin = searchButtonBounds
        searchButtonBounds = null
        if (isDualPaneMode && DualPaneCollapseUtil.isCollapsed) {
            expandListPane()
            
            origin = null
        }
        listLayer.open(ListPaneLayerRoot.SEARCH, revealFrom = origin)
    }

    private fun onHomeTabClick(index: Int) {
        
        
        
        if (isDualPaneMode && DualPaneCollapseUtil.isCollapsed) {
            expandListPane()
        }
        
        listLayer.close()
        binding.indexViewpager.setCurrentItem(index, false)
    }

    
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

    
    private fun syncConversationAndFocusState() {
        conversationSettingsManager.syncConversationSettings()
        groupFocusRepository.requestRefresh("index-start")
    }

    private fun syncContactAndGroupInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            ContactorUtil.fetchAndSaveContactors(false)
            groupUtil.syncAllGroupAndAllGroupMembers(forceFetch = false)
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


    





    override fun shouldApplyNavigationBarPadding(): Boolean = false

    




    override fun shouldApplyStatusBarPadding(): Boolean = false

    override fun participatesInSideChrome(): Boolean = true

    
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
        
        pushUtil.initFCMPush()
    }

    
    private var loggedFcmResult: FcmInitResult? = null

    private fun observeFcmInitResult() {
        pushUtil.fcmInitResult
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { result ->
                
                
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
                        
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        checkInsiderUpdate()
        reconcileCriticalAlertBypass()
    }

    private fun checkDisappearingMessage() {
        messageArchiveManager.startCheckTask()
        
        lifecycleScope.launch(Dispatchers.IO) {
            serverTimeSyncer.ensureAnchored()
        }
    }

    private fun startFileCleanupTask() {
        
        
        
        GlideCacheKeyManager.warmUp(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            FileUtil.clearDraftAttachmentsDirectory()
            FileUtil.deleteMessageAttachmentEmptyDirectories()
            
            LegacyPlaintextAttachmentMigration.runIfNeeded()
            
            LegacyPlaintextAttachmentMigration.purgeRedundantPlaintextIfNeeded()
            AudioAmplitudesHelper.clearStaleTemps()
            
            LegacyPlaintextAvatarCleanup.runIfNeeded()
        }
        
        
        forwardAttachmentMigration.start()
    }

    
    private fun cleanEmptyRooms() {
        lifecycleScope.launch(Dispatchers.IO) {
            val activeConversationConfig = globalConfigsManager.getActiveConversationConfig()
            WCDBUpdateService.cleanEmptyRooms(activeConversationConfig)
        }
    }

    
    private fun handleIntentData(intent: Intent, isRecreation: Boolean = false) {
        testReturnGeneration++
        if (intent.hasCategory(NotificationTestReturnRoute.CATEGORY)) {
            handleNotificationTestReturn(intent)
            return
        }
        
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
                    
                    if (handledTestReturns.size > 32) handledTestReturns.remove(handledTestReturns.first())
                },
            )
        }
    }

    



    private fun handleDeeplinkFromIntent(intent: Intent, isRecreation: Boolean) {
        val linkCategory = intent.getIntExtra(LinkDataEntity.LINK_CATEGORY, -1)
        val pushData = intent.getStringExtra("pushData")
        val groupId = intent.getStringExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID)
        val contactId = intent.getStringExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID)
        val schemeUri = intent.data

        var linkDataEntity: LinkDataEntity? = null

        
        if (linkCategory != -1) {
            linkDataEntity = LinkDataEntity(linkCategory, groupId, contactId, null)
        }
        
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
        
        else if (schemeUri != null) {
            val scheme = schemeUri.scheme
            if (scheme != null && AppScheme.allSchemes.contains(scheme)) {
                linkDataEntity = LinkDataEntity(LinkDataEntity.CATEGORY_SCHEME, null, null, schemeUri)
            }
        }

        
        
        
        val chatAlreadyOpened = isRecreation && linkDataEntity?.category.let {
            it == LinkDataEntity.CATEGORY_PUSH || it == LinkDataEntity.CATEGORY_MESSAGE
        }
        if (!chatAlreadyOpened) linkDataEntity?.let { handleDeeplink(it) }
    }

    
    private fun openExternalConversation(target: ConversationTarget) {
        navigator.openConversation(target, PaneNavigator.Origin.External)
    }

    private fun handleDeeplink(linkData: LinkDataEntity) {
        
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
        
        
        paneReconciler.reconcile("onStart")
    }

    override fun onStop() {
        super.onStop()
        paneStarted = false
        staleMonitor.onStop()
    }

    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        staleOverridePanel = StaleLayoutOverride.applyIfActive(this, newBase)
    }

    override fun onHostDisplayChanged(display: Display) {
        if (::staleMonitor.isInitialized) staleMonitor.onHostDisplayChanged()
    }

    override fun onDestroy() {
        homeGlassBackdrop?.close()
        homeGlassBackdrop = null
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
                    handleSharedFileUri(uri)
                } else {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (sharedText != null) {
                        selectChatsUtils.showChatSelectAndSendDialog(
                            this@IndexActivity,
                            sharedText,
                        )
                    } else {
                        L.w { "[Share] Received share intent but neither URI nor text found. Intent type: ${intent.type}" }
                    }
                }
            } catch (e: Exception) {
                L.e { "SharedContent Received Exception: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun handleSharedFileUri(uri: Uri) {
        val arrival = ShareImportGate.arrive()
        lifecycleScope.launch {
            val file = stageSharedFile(uri, arrival) ?: return@launch
            try {
                
                selectChatsUtils.showChatSelectAndSendDialog(
                    this@IndexActivity,
                    "",
                    file = file,
                    onFileReleased = { file.delete() },
                )
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    }

    







    private suspend fun stageSharedFile(uri: Uri, arrival: Int): File? {
        val rejected = withContext(Dispatchers.IO) {
            when {
                SharedUriGuard.pointsIntoAppStorage(uri, SharedUriGuard.appStorageRoots(this@IndexActivity)) -> "file_into_app_storage"
                SharedUriGuard.isOwnProviderUri(this@IndexActivity, uri) -> "own_provider"
                else -> null
            }
        }
        if (rejected != null) {
            L.w { "[Share] rejected shared uri reason=$rejected" }
            ToastUtil.showLong(R.string.unsupported_file_type)
            return null
        }

        val (fileSize, extension) = withContext(Dispatchers.IO) {
            val type = contentResolver.getType(uri)
            FileUtil.getFileSize(uri) to (type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "tmp")
        }
        if (fileSize >= FileUtil.MAX_SUPPORT_FILE_SIZE) {
            ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
            return null
        }

        var input: InputStream? = null
        var staged: File? = null
        try {
            withContext(Dispatchers.IO) { input = openSharedStream(uri) }
            val stream = input ?: run {
                ToastUtil.showLong(R.string.unsupported_file_type)
                return null
            }
            
            if (!ShareImportGate.begin(arrival)) {
                L.i { "[Share] import superseded by a newer share" }
                return null
            }
            var outcome: Result<File>? = null
            ShareImportGate.mutex.withLock {
                
                withContext(NonCancellable + Dispatchers.IO) {
                    outcome = runCatching { copyToStaging(stream, extension, fileSize, arrival) }
                    staged = outcome?.getOrNull()
                }
            }
            if (!currentCoroutineContext().isActive) return null
            when (val error = outcome?.exceptionOrNull()) {
                null -> return staged.also { staged = null }
                is ShareImportSupersededException -> L.i { "[Share] import superseded by a newer share" }
                is SizeLimitExceededException -> {
                    
                    L.w { "[Share] shared file over limit reported=$fileSize max=${error.maxBytes}" }
                    ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                }
                else -> {
                    L.e { "[Share] copy shared file failed: ${error.stackTraceToString()}" }
                    ToastUtil.showLong(R.string.unsupported_file_type)
                }
            }
            return null
        } finally {
            runCatching { input?.close() }
            
            staged?.delete()
        }
    }

    private fun getUriFromIntent(intent: Intent): Uri? {
        
        intent.clipData?.let { clipData ->
            if (clipData.itemCount > 0) {
                val uri = clipData.getItemAt(0).uri
                L.d { "[IndexActivity] Got URI from ClipData: $uri" }
                return uri
            }
        }

        
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }

        L.d { "[IndexActivity] Got URI from EXTRA_STREAM: $uri" }
        return uri
    }

    



    private fun openSharedStream(uri: Uri): InputStream? {
        try {
            contentResolver.openInputStream(uri)?.let { return it }
            L.w { "[IndexActivity] openInputStream returned null for ${uri.redactedForLog()}" }
        } catch (e: Exception) {
            L.w { "[IndexActivity] openInputStream failed for ${uri.redactedForLog()}: ${e.stackTraceToString()}" }
        }
        try {
            contentResolver.openFileDescriptor(uri, "r")?.let { return ParcelFileDescriptor.AutoCloseInputStream(it) }
            L.w { "[IndexActivity] openFileDescriptor returned null for ${uri.redactedForLog()}" }
        } catch (e: Exception) {
            L.e { "[IndexActivity] openFileDescriptor failed for ${uri.redactedForLog()}: ${e.stackTraceToString()}" }
        }
        return null
    }

    




    private fun copyToStaging(input: InputStream, extension: String, reportedSize: Long, ticket: Int): File {
        val remaining = FileUtil.remainingStagingBytes(FilePathManager.shareImportDir)
        if (remaining <= 0 || reportedSize >= remaining) throw SizeLimitExceededException(remaining.coerceAtLeast(0))
        val maxBytes = minOf(FileUtil.MAX_SUPPORT_FILE_SIZE - 1L, remaining - 1L)

        
        
        
        val file = File(FilePathManager.shareImportDir, "shared_file_${System.currentTimeMillis()}.$extension")
        try {
            ShareImportGate.copying(ticket, input) { stream -> file.outputStream().use { stream.copyToBounded(it, maxBytes) } }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    





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

    

    


    private fun setupDualPaneLayout() {
        
        
        isDualPaneMode = resources.getBoolean(com.difft.android.R.bool.dual_pane_layout_active)

        if (isDualPaneMode) {
            
            binding.root.viewTreeObserver.addOnGlobalLayoutListener {
                (binding.root.background as? PaneSplitBackgroundDrawable)?.onPanesLaidOut()
            }

            
            applyListPaneWidth(TextSizeUtil.isLarger)
            
            
            binding.root.post { applyListPaneWidth(TextSizeUtil.isLarger) }
        }
    }

    




















    private fun applyListPaneWidth(isLarger: Boolean) {
        if (!isDualPaneMode) return
        val listPane = findViewById<View>(com.difft.android.R.id.list_pane) ?: return
        
        
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

    




    private fun resolveListTargetPx(isLarger: Boolean): Int {
        val available = availablePaneSpacePx()
        val budget = paneBudget()
        
        
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

    












    private fun setupDualPaneDivider() {
        if (!isDualPaneMode) return
        val divider = findViewById<DraggableDividerView>(com.difft.android.R.id.divider_pane) ?: return
        val listPane = findViewById<View>(com.difft.android.R.id.list_pane) ?: return

        
        
        
        
        var virtualWidthPx: Int? = null

        divider.onDrag = { rawDelta, isEnd, rawVelocityX, cancelled ->
            
            
            
            val rtl = listPane.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val delta = if (rtl) -rawDelta else rawDelta
            val velocityX = if (rtl) -rawVelocityX else rawVelocityX
            
            if (!isEnd) BubbleWidth.freeze()
            when {
                
                paneAnimator?.isRunning == true -> virtualWidthPx = null

                DualPaneCollapseUtil.isCollapsed -> {
                    val overshoot = ((virtualWidthPx ?: 0) + delta).coerceAtLeast(0)
                    if (isEnd) {
                        virtualWidthPx = null
                        
                        if (!cancelled && (overshoot >= snapTriggerPx() || velocityX >= flingVelocityPx())) {
                            divider.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            expandListPane()
                        } else {
                            
                            animateListPaneWidth(COLLAPSED_LIST_WIDTH_PX, frozenContentPx = binding.listContent.layoutParams.width)
                        }
                    } else {
                        virtualWidthPx = overshoot
                        
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
                    
                    
                    val listMaxPx = (available - budget.dragFloorPx).coerceAtLeast(budget.listMinPx)

                    val virtual = ((virtualWidthPx ?: listPane.layoutParams.width) + delta)
                        .coerceAtMost(listMaxPx)
                    val overshoot = (budget.listMinPx - virtual).coerceAtLeast(0)
                    
                    
                    val displayWidth = if (overshoot == 0) virtual else budget.listMinPx - rubberPx(overshoot)

                    if (isEnd) {
                        virtualWidthPx = null
                        
                        
                        
                        
                        
                        val commitCollapse = !cancelled &&
                            (overshoot >= snapTriggerPx() || velocityX <= -flingVelocityPx())
                        when {
                            commitCollapse -> {
                                divider.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                                collapseListPane()
                            }
                            overshoot > 0 -> {
                                
                                animateListPaneWidth(budget.listMinPx)
                                if (available > 0) DualPaneRatioUtil.updateRatio(budget.listMinPx.toFloat() / available)
                            }
                            else -> {
                                if (available > 0) DualPaneRatioUtil.updateRatio(displayWidth.toFloat() / available)
                                
                                refreshDetailPaneMessageBubbles()
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

        
        divider.setOnClickListener {
            if (DualPaneCollapseUtil.isCollapsed) expandListPane()
        }

        updateDividerAccessibility(divider)
    }

    
    private fun collapseListPane() {
        if (DualPaneCollapseUtil.isCollapsed) return
        DualPaneCollapseUtil.setCollapsed(true)
        animateListPaneWidth(COLLAPSED_LIST_WIDTH_PX)
        findViewById<DraggableDividerView>(com.difft.android.R.id.divider_pane)
            ?.let { updateDividerAccessibility(it) }
    }

    
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

    









    private fun animateListPaneWidth(targetPx: Int, frozenContentPx: Int = 0) {
        val listPane = findViewById<View>(com.difft.android.R.id.list_pane) ?: return
        val content = binding.listContent
        
        
        paneAnimator?.cancel()
        paneAnimator = null
        BubbleWidth.freeze()

        val startPx = listPane.layoutParams.width
        if (startPx == targetPx) {
            
            
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

    



    private fun refreshDetailPaneMessageBubbles() {
        BubbleWidth.unfreeze()
    }

    private fun availablePaneSpacePx(): Int {
        
        
        
        
        
        
        
        val insetPaddingPx = binding.root.paddingLeft + binding.root.paddingRight
        
        
        
        val sideBarPx = sideChrome?.reservedPx ?: 0
        return (WindowSizeClassUtil.getWindowWidthPx(this) - insetPaddingPx - sideBarPx).coerceAtLeast(0)
    }

    
    private fun dualPaneMinWidthDp(): Float {
        if (!isDualPaneMode) return 0f
        val px = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_list_min_width) +
            resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_detail_min_width)
        return px / resources.displayMetrics.density
    }

    
    private data class PaneBudget(
        val listMinPx: Int,
        val listDefaultPx: Int,
        val dragFloorPx: Int
    )

    










    private fun paneBudget(): PaneBudget = PaneBudget(
        listMinPx = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_list_min_width),
        listDefaultPx = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_list_default_width),
        dragFloorPx = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_detail_drag_min_width)
    )

    private companion object {
        
        
        const val COLLAPSED_LIST_WIDTH_PX = 1

        
        
        const val COLLAPSE_SNAP_TRIGGER_DP = 48

        
        
        const val COLLAPSE_FLING_VELOCITY_DP_S = 1000

        
        const val RUBBER_BAND_MAX_DP = 32

        const val PANE_SNAP_ANIM_MS = 220L

        
        
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

        
        
        override fun onCurrentDetailEmptied() {
            expandListPane()
        }

        
        override fun onSelectTabRequested(tab: Int) {
            if (::listLayer.isInitialized) listLayer.close()
            binding.indexViewpager.setCurrentItem(tab, false)
        }
    }

    



    private fun notifyListFragmentSelectionChanged() {
        if (!isDualPaneMode) return
        val selectedId = navigator.currentSelectedId
        supportFragmentManager.fragments.forEach { fragment ->
            (fragment as? DualPaneSelectionListener)?.updateDualPaneSelection(selectedId)
        }
    }


    override fun onOneOnOneConversationSelected(contactId: String, jumpMessageTimestamp: Long?) {
        openConversationFromCurrentTab(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, contactId, jumpMessageTimestamp))
    }

    override fun onGroupConversationSelected(groupId: String, jumpMessageTimestamp: Long?) {
        openConversationFromCurrentTab(ConversationTarget(ConversationTarget.Kind.GROUP, groupId, jumpMessageTimestamp))
    }

    
    private fun openConversationFromCurrentTab(target: ConversationTarget) {
        navigator.openConversation(target, PaneNavigator.Origin.Tab(navigator.currentTab))
    }

    override fun onContactDetailSelected(contactId: String) {
        if (isDualPaneMode) {
            navigator.replaceRoot(navigator.currentTab, IndexDetailPages.contactDetail(contactId), selectionId = contactId)
        } else {
            
            ContactDetailBottomSheetDialogFragment.show(this, contactId)
        }
    }

    override fun onContactCardSelected(request: ContactCardRequest): Boolean {
        if (!isDualPaneMode) return false
        navigator.replaceRoot(navigator.currentTab, IndexDetailPages.contactDetail(request), selectionId = request.contactId)
        return true
    }

    override fun onGroupCardSelected(groupId: String): Boolean {
        if (!isDualPaneMode) return false
        navigator.replaceRoot(navigator.currentTab, IndexDetailPages.groupCard(groupId), selectionId = groupId)
        return true
    }

    override fun openConversation(target: ConversationTarget) {
        openConversationFromCurrentTab(target)
    }

    override fun currentConversationOnScreen(): String? =
        if (::navigator.isInitialized) navigator.currentConversationOnScreen() else null

    override val detailCoversList: StateFlow<Boolean>
        get() = if (::listCovered.isInitialized) listCovered else navigator.detailCoversList

    override val conversationCoversList: StateFlow<Boolean>
        get() = navigator.detailCoversList


    
    override fun openSearchLayer(): Boolean {
        listLayer.open(ListPaneLayerRoot.SEARCH)
        return true
    }

    
    override fun openFoldedGroupsLayer(): Boolean {
        listLayer.open(ListPaneLayerRoot.FOLDED_GROUPS)
        return true
    }

    



    override fun openCreateGroupLayer(preselectedIds: List<String>): Boolean {
        if (!isDualPaneMode) return false
        if (DualPaneCollapseUtil.isCollapsed) expandListPane()
        listLayer.open(ListPaneLayerRoot.CREATE_GROUP, args = CreateGroupFragment.rootArgs(preselectedIds))
        return true
    }

    
    override fun showsCreateGroup(): Boolean =
        (listLayer.active && listLayer.root == ListPaneLayerRoot.CREATE_GROUP) ||
            supportFragmentManager.shownDetailStackTop() is CreateGroupFragment


    override val homeTabBarObstructionPx: StateFlow<Int>
        get() = homeTabBarObstruction

    



    override fun showDetailFragment(fragment: Fragment, tag: String?) {
        navigator.showMePage(fragment)
    }

    




    override fun getChatMessageListFragment(): ChatMessageListFragment? =
        when (val root = if (::navigator.isInitialized) navigator.currentConversationRoot() else null) {
            is ChatFragment -> root.getChatMessageListFragment()
            is GroupChatFragment -> root.getChatMessageListFragment()
            else -> null
        }


    override fun focusCurrentChatInputIfMatches(conversationId: String): Boolean {
        if (!::navigator.isInitialized || navigator.currentConversationOnScreen() != conversationId) return false
        (navigator.currentConversationRoot() as? ChatFragment)?.focusInputAndShowKeyboard()
        return true
    }
}
