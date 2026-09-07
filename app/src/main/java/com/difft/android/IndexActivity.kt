package com.difft.android

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
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
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DualPaneCollapseUtil
import com.difft.android.base.utils.DualPaneRatioUtil
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.security.SafeLinkOpener
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.call.util.FullScreenPermissionHelper
import com.difft.android.base.utils.NetworkUtils
import com.difft.android.chat.R
import com.difft.android.chat.contacts.ContactsFragment
import com.difft.android.chat.contacts.WeakContactReconciler
import com.difft.android.chat.contacts.contactsdetail.ContactDetailFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.attachment.migration.ForwardAttachmentMigration
import com.difft.android.chat.media.LegacyPlaintextAttachmentMigration
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.chat.media.LegacyPlaintextAvatarCleanup
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupChatFragment
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.recent.DualPaneSelectionListener
import com.difft.android.chat.recent.RecentChatFragment
import com.difft.android.chat.recent.RecentChatUtil
import com.difft.android.chat.recent.RecentChatViewModel
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatBackgroundDrawable
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
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.wcdb
import com.difft.android.chat.messages.FailedMessageProcessor
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.messages.MessageServiceManager
import com.difft.android.chat.messages.PendingMessageProcessor
import com.difft.android.chat.util.AppIconBadgeManager
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.websocket.WebSocketManager
import com.difft.android.views.DraggableDividerView
import java.io.File
import javax.inject.Inject
import kotlin.system.exitProcess


@AndroidEntryPoint
class IndexActivity : BaseActivity(), ConversationNavigationCallback, ChatMessageListProvider, ChatInputFocusable {
    private lateinit var binding: ActivityIndexBinding

    // Dual-pane layout support for large screens
    // Using a marker view to detect dual-pane mode (w673dp-h480dp layout)
    override var isDualPaneMode = false
        private set
    override val currentSelectedConversationId: String?
        get() = currentConversationId
    private var currentConversationId: String? = null

    // Store detail fragment for each tab (tab index -> Fragment)
    // This allows preserving fragment state when switching tabs
    private val tabDetailFragments = mutableMapOf<Int, Fragment?>()
    private var currentTabIndex = 0

    // Set on the first onRestoreInstanceState; a second one must not replay pager state.
    private var hierarchyStateRestored = false

    // Drives the list-pane collapse/expand snap; owns list_pane's width while running.
    private var paneAnimator: ValueAnimator? = null

    // Enabled only while the list pane is collapsed: back re-opens the list.
    private var collapsedBackCallback: OnBackPressedCallback? = null

    // True once restoreDetailFragmentsState has run: the ViewPager2 restore dispatches
    // onPageSelected BEFORE the posted reclaim, and that premature empty-tab pass must not
    // auto-expand a persisted collapsed pane.
    private var detailRestoreSettled = false

    // Recreation (rotate / fold) restores the persisted collapse even over an empty detail
    // pane; only a true cold start silently un-collapses it (blank-first-screen avoidance).
    private var recreatedFromSavedState = false

    private val indicators by lazy {
        listOf(
            binding.indicatorviewChats,
            binding.indicatorviewContacts,
            binding.indicatorviewMe
        )
    }

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
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var messageServiceManager: MessageServiceManager

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recreatedFromSavedState = savedInstanceState != null
        binding = ActivityIndexBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup dual-pane layout for large screens (w673dp-h480dp)
        setupDualPaneLayout()

        val density = resources.displayMetrics.density
        L.i { "[IndexActivity] screen swDp=${resources.configuration.smallestScreenWidthDp} widthDp=${(WindowSizeClassUtil.getWindowWidthPx(this) / density).toInt()} heightDp=${(WindowSizeClassUtil.getWindowHeightPx(this) / density).toInt()} dualPane=$isDualPaneMode" }

        // Load and emit text size early to avoid ANR in UI components
        TextSizeUtil.loadAndEmitTextSize()
        DualPaneRatioUtil.loadAndEmit()
        DualPaneCollapseUtil.loadAndEmit()

        TextSizeUtil.textSizeState
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { textSize ->
                val isLarger = textSize == TextSizeUtil.TEXT_SIZE_LAGER
                indicators.forEach { it.updateSize(isLarger) }
                applyListPaneWidth(isLarger)
            }
            .launchIn(lifecycleScope)

        DualPaneRatioUtil.ratioState
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { applyListPaneWidth(TextSizeUtil.isLarger) }
            .launchIn(lifecycleScope)

        setupDualPaneDivider()

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

            registerOnPageChangeCallback(object : OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    // position 0 是会话列表，不允许截屏
//                    ScreenShotUtil.refreshWithPagePolicy(this@IndexActivity, position != 0)
                    selectIndicator(position)
                    // Handle detail pane visibility when tab changes in dual-pane mode
                    handleTabChangeForDualPane(position)
                    // Sync root background to the active tab so edge-to-edge system
                    // bars match the page underneath (recent/contacts = bg1 flat;
                    // me = bg settings-idiom).
                    applyRootBackgroundForTab(position)
                }
            }.also {
                // 初始化时手动触发一次，因为 OnPageChangeCallback 默认不会触发第一页
                it.onPageSelected(currentItem)
            })

            indicators.forEach {
                it.setOnClickListener { view ->
                    val index = indicators.indexOf(view)
                    if (index < 0) return@setOnClickListener

                    // Tapping any rail tab is the discoverable way back from a collapsed
                    // list pane (the divider handle is the other).
                    if (isDualPaneMode && DualPaneCollapseUtil.isCollapsed) {
                        expandListPane()
                    }
                    binding.indexViewpager.setCurrentItem(index, false)
                }
            }
        }

        // Reclaim recreation-restored detail fragments once the adapter + restored currentItem are ready.
        binding.indexViewpager.post { restoreDetailFragmentsState() }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                RecentChatUtil.emitChatDoubleTab()
                return true
            }
        })

        binding.indicatorviewChats.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        // Handle Intent data (deeplink or external share)
        handleIntentData(intent)
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

        checkEmulator()

        checkRoot()

        checkUpdate()

        syncContactAndGroupInfo()

        setUserProfile()

        checkDisappearingMessage()

        startFileCleanupTask()

        observeAndUpdateUnreadMessageCountBadge()

        requestNotificationPermission()

        globalConfigsManager.syncMineConfigs()

        // Sync conversation settings
        conversationSettingsManager.syncConversationSettings()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        fetchCallServiceUrlAndCache()

        fetchFeatureGrayConfigs()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerPermission { permissionState ->
                L.i { "[Notification] requestNotificationPermission permissionState:$permissionState" }
            }.launchSinglePermission(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun observeAndUpdateUnreadMessageCountBadge() {
        recentChatViewModel.allRecentRoomsStateFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach {
                val unreadNotMuteMessageCount =
                    it.filter { it.isMuted.not() }.sumOf { room -> room.unreadMessageNum }
                val unreadMuteMessageCount =
                    it.filter { it.isMuted }.sumOf { room -> room.unreadMessageNum }
                L.i { "[IndexActivity] observeAndUpdateUnreadMessageCountBadge:$unreadNotMuteMessageCount unreadMuteMessageCount:$unreadMuteMessageCount " }
                if (unreadNotMuteMessageCount != 0) {
                    displayBadge(
                        R.drawable.chat_missing_number_bg,
                        unreadNotMuteMessageCount
                    )
                } else {
                    displayBadge(R.drawable.chat_missing_number_bg_muted, unreadMuteMessageCount)
                }
                userManager.update { unreadMsgNum = unreadNotMuteMessageCount }
                appIconBadgeManager.updateAppIconBadgeNum(unreadNotMuteMessageCount)
            }
            .launchIn(lifecycleScope)
    }

    private fun displayBadge(backgroundColorRes: Int, unreadMessageCount: Int) {
        val badgeText = when {
            unreadMessageCount <= 0 -> null
            unreadMessageCount > 99 -> "99+"
            else -> unreadMessageCount.toString()
        }

        binding.indicatorviewChats.setBadgeText(badgeText, backgroundColorRes)
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
        indicators
            .forEachIndexed { index, indicator ->
                val shouldSelected = index == indicatorPosition
                indicator.isSelected = shouldSelected
            }
    }

    private fun applyRootBackgroundForTab(position: Int) {
        // Recent (0) and Contacts (1) tabs are flat-surface immersive lists → bg1
        // Me (2) tab is settings-idiom (gray page + elevated cards) → bg
        // In edge-to-edge mode the activity root drives the status/nav bar color
        // since no opaque system-bar background is set.
        val bgRes = if (position == 2) {
            com.difft.android.base.R.color.bg
        } else {
            com.difft.android.base.R.color.bg1
        }
        binding.root.setBackgroundResource(bgRes)
    }

    private fun checkUpdate() {
        if (environmentHelper.isInsiderChannel()) return
        lifecycleScope.launch {
            delay(2000)
            updateManager.checkUpdate(this@IndexActivity, false)
        }
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

    private fun observeFcmInitResult() {
        pushUtil.fcmInitResult
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { result ->
                when (result) {
                    is FcmInitResult.PlayServicesUnavailable -> {
                        L.w { "[Push][fcm] Google Play Services not available, status:${result.statusCode}" }
                        handleFcmUnavailable()
                    }

                    is FcmInitResult.Failure -> {
                        L.w { "[Push][fcm] FCM initialization failed: ${result.reason}" }
                        handleFcmUnavailable()
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
        checkNotificationFullScreenPermission()
        checkNotificationPermission()
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
     * 检测模拟器
     */
    private fun checkEmulator() {
        if (!BuildConfig.DEBUG) {
            lifecycleScope.launch {
                val emulatorSafe = withContext(Dispatchers.IO) {
                    SecurityLib.checkEmulator()
                }
                if (!emulatorSafe) {
                    ComposeDialogManager.showMessageDialog(
                        context = this@IndexActivity,
                        title = getString(R.string.app_sign_error_title),
                        message = getString(R.string.emulator_risk_tips),
                        confirmText = getString(R.string.app_close_application),
                        cancelText = getString(R.string.app_ignore),
                        cancelable = false,
                        onConfirm = {
                            Process.killProcess(Process.myPid())
                            exitProcess(0)
                        }
                    )
                }
            }
        }
    }

    /**
     * 检测系统root
     */
    private fun checkRoot() {
        if (!BuildConfig.DEBUG) {
            lifecycleScope.launch {
                val rootSafe = withContext(Dispatchers.IO) {
                    SecurityLib.checkRoot()
                }
                if (!rootSafe) {
                    ComposeDialogManager.showMessageDialog(
                        context = this@IndexActivity,
                        title = getString(R.string.app_sign_error_title),
                        message = getString(R.string.root_risk_tips),
                        confirmText = getString(R.string.app_close_application),
                        cancelText = getString(R.string.app_ignore),
                        cancelable = false,
                        onConfirm = {
                            Process.killProcess(Process.myPid())
                            exitProcess(0)
                        }
                    )
                }
            }
        }
    }


    /**
     * 处理 FCM 不可用的情况
     *
     * 逻辑：
     * 1. 如果服务已运行 → 不处理
     * 2. 如果服务未运行：
     *    - 如果用户允许自动启动（autoStartMessageService = true）：
     *      - 检查启动条件
     *      - 条件满足 → 自动启动（不弹窗）
     *      - 条件不满足 → 弹窗引导用户开启权限
     *    - 如果用户主动关闭过服务（autoStartMessageService = false）：
     *      - 弹窗提示用户开启服务
     */
    private fun handleFcmUnavailable() {
        // 1. 服务已运行，不处理
        if (MessageForegroundService.isRunning) {
            L.i { "[MessageService] Service already running, no action needed" }
            return
        }

        // 2. 检查用户意图
        val autoStartMessageService = userManager.getUserData()?.autoStartMessageService ?: true

        if (autoStartMessageService) {
            // 用户允许自动启动，检查条件
            if (messageServiceManager.checkBackgroundConnectionRequirements()) {
                // 条件满足，自动启动服务
                L.i { "[MessageService] Auto-starting service (conditions met)" }
                messageServiceManager.startService()
            } else {
                // 条件不满足，弹窗引导用户开启权限
                L.w { "[MessageService] Cannot auto-start, showing settings dialog" }
                showStartMessageServiceTipsDialog()
            }
        } else {
            // 用户主动关闭过，弹窗提示
            L.i { "[MessageService] User disabled service, showing enable dialog" }
            showStartMessageServiceTipsDialog()
        }
    }

    /**
     * 显示后台连接提示弹窗
     * - 如果当前版本已经显示过（用户点击了"暂不开启"），则不再显示
     * - 只有用户点击"暂不开启"时，才记录当前版本号，此版本不再弹窗
     * - 如果用户点击"前往设置"，不记录版本号，下次还会弹窗
     */
    private fun showStartMessageServiceTipsDialog() {
        if (!pushUtil.canShowFcmUnavailableDialog()) {
            L.d { "[MessageService] Dialog already shown in this application session" }
            return
        }

        val messageServiceTipsShowedVersion = userManager.getUserData()?.messageServiceTipsShowedVersion
        if (messageServiceTipsShowedVersion == PackageUtil.getAppVersionName()) {
            L.i { "[MessageService] Tips dialog already shown in this version" }
            return
        }

        // 设置标记，防止重复弹窗
        pushUtil.markFcmUnavailableDialogShown()

        ComposeDialogManager.showMessageDialog(
            context = this@IndexActivity,
            title = getString(R.string.tip),
            message = getString(R.string.notification_no_google_tip),
            confirmText = getString(R.string.notification_go_to_settings),
            cancelText = getString(R.string.notification_ignore),
            cancelable = false,
            onConfirm = {
                BackgroundConnectionSettingsActivity.startActivity(this@IndexActivity)
            },
            onCancel = {
                // 用户点击"暂不开启"，记录版本号，此版本不再弹窗
                userManager.update {
                    this.messageServiceTipsShowedVersion = PackageUtil.getAppVersionName()
                }
            }
        )
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
    private fun handleIntentData(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> handleShareIntent(intent)
            else -> handleDeeplinkFromIntent(intent)
        }
    }

    /**
     * Handle deeplink data from Intent (notification click, push, scheme URL).
     * Priority: linkCategory > pushData > schemeUri
     */
    private fun handleDeeplinkFromIntent(intent: Intent) {
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
        
        linkDataEntity?.let { handleDeeplink(it) }
    }

    private fun handleDeeplink(linkData: LinkDataEntity) {
        // Trigger screen lock check for deeplink scenario
        (application as com.difft.android.app.TempTalkApplication).triggerScreenLockCheck()

        when (linkData.category) {
            LinkDataEntity.CATEGORY_PUSH, LinkDataEntity.CATEGORY_MESSAGE -> {
                if (!TextUtils.isEmpty(linkData.gid)) {
                    if (ValidatorUtil.isGid(linkData.gid.toString())) {
                        GroupChatContentActivity.startActivity(
                            this,
                            linkData.gid ?: ""
                        )
                    } else {
                        L.e { "[Deeplink] CATEGORY_PUSH gid:${linkData.gid} is invalid" }
                    }
                } else if (!TextUtils.isEmpty(linkData.uid)) {
                    if (ValidatorUtil.isUid(linkData.uid.toString())) {
                        ChatActivity.startActivity(this, linkData.uid ?: "")
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

    override fun onDestroy() {
        paneAnimator?.cancel()
        paneAnimator = null
        super.onDestroy()
    }

    private fun setUserProfile() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loginRepo.setProfile()
                }
                L.i { "setUserProfile success" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[IndexActivity] setUserProfile error: ${e.stackTraceToString()}" }
            }
        }
    }

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    private var checkNotificationPermissionIgnore = false
    private var checkNotificationFullScreenPermissionIgnore = false
    private var checkNotificationPermissionDialog: ComposeDialog? = null
    private var checkNotificationFullScreenPermissionDialog: ComposeDialog? = null
    private var checkNotificationFullScreenPermissionJob: Job? = null

    /**
     * 检查通知权限并显示引导对话框
     */
    private fun checkNotificationPermission() {
        if (checkNotificationPermissionIgnore) return

        val notificationPermissionCheckedVersion = userManager.getUserData()?.checkNotificationPermission
        if (notificationPermissionCheckedVersion == PackageUtil.getAppVersionName()) return

        lifecycleScope.launch {
            val canShow = withContext(Dispatchers.IO) {
                messageNotificationUtil.canShowNotifications()
            }
            if (!canShow) {
                if (checkNotificationPermissionDialog == null) {
                    checkNotificationPermissionDialog = ComposeDialogManager.showMessageDialog(
                        context = this@IndexActivity,
                        title = getString(R.string.tip),
                        message = getString(R.string.notification_no_permission_tip1, PackageUtil.getAppName()),
                        confirmText = getString(R.string.notification_go_to_settings),
                        cancelText = getString(R.string.notification_ignore),
                        onConfirm = {
                            messageNotificationUtil.openNotificationSettings(this@IndexActivity)
                            checkNotificationPermissionIgnore = true
                        },
                        onCancel = {
                            userManager.update {
                                this.checkNotificationPermission = PackageUtil.getAppVersionName()
                            }
                            checkNotificationPermissionIgnore = true
                        },
                        onDismiss = {
                            checkNotificationPermissionDialog = null
                        }
                    )
                }
            } else {
                checkNotificationPermissionDialog?.dismiss()
                checkNotificationPermissionDialog = null
            }
        }
    }

    private fun checkNotificationFullScreenPermission() {
        if (checkNotificationFullScreenPermissionIgnore) return

        checkNotificationFullScreenPermissionJob?.cancel()
        checkNotificationFullScreenPermissionJob = lifecycleScope.launch {
            val hasPermission = withContext(Dispatchers.IO) {
                messageNotificationUtil.hasFullScreenNotificationPermission()
            }
            if (checkNotificationFullScreenPermissionIgnore) return@launch
            if (!hasPermission) {
                if (checkNotificationFullScreenPermissionDialog == null) {
                    val message = FullScreenPermissionHelper.getNoPermissionTip()
                    checkNotificationFullScreenPermissionDialog = ComposeDialogManager.showMessageDialog(
                        context = this@IndexActivity,
                        title = getString(R.string.tip),
                        message = message,
                        confirmText = getString(R.string.notification_go_to_settings),
                        cancelText = getString(R.string.notification_ignore),
                        onConfirm = {
                            messageNotificationUtil.openFullScreenNotificationSettings(this@IndexActivity)
                        },
                        onCancel = {
                            checkNotificationFullScreenPermissionIgnore = true
                        },
                        onDismiss = {
                            checkNotificationFullScreenPermissionDialog = null
                        }
                    )
                }
            } else {
                checkNotificationFullScreenPermissionDialog?.dismiss()
                checkNotificationFullScreenPermissionDialog = null
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
            // 优先判断文件大小是否超过200MB
            val fileSize = withContext(Dispatchers.IO) {
                FileUtil.getFileSize(uri)
            }

            if (fileSize >= FileUtil.MAX_SUPPORT_FILE_SIZE) {
                ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                return@launch
            }

            val file = withContext(Dispatchers.IO) {
                runCatching { copyUriToFile(uri) }
                    .onFailure { L.e { "copyUriToFile failed: ${it.stackTraceToString()}" } }
                    .getOrNull()
            }

            if (file == null) {
                ToastUtil.showLong(R.string.unsupported_file_type)
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
        val file = File(cacheDir, fileName)

        // Copy the bytes, capturing the exact failure so "unsupported file type" reports have a root
        // cause in logcat. Try openInputStream first, then fall back to openFileDescriptor — some
        // providers (e.g. our decrypting EncryptedAttachmentProvider, OEM file managers) succeed on
        // one path but not the other.
        if (!copyUriContent(uri, file)) {
            file.delete()
            throw java.io.FileNotFoundException("[IndexActivity] no readable stream for shared uri=${uri.redactedForLog()} (mime=$mimeType)")
        }

        return file
    }

    private fun copyUriContent(uri: Uri, target: File): Boolean {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
                return true
            }
            L.w { "[IndexActivity] openInputStream returned null for ${uri.redactedForLog()}" }
        }.onFailure {
            L.w { "[IndexActivity] openInputStream failed for ${uri.redactedForLog()}: ${it.stackTraceToString()}" }
        }

        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                return true
            }
        }.onFailure {
            L.e { "[IndexActivity] openFileDescriptor failed for ${uri.redactedForLog()}: ${it.stackTraceToString()}" }
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
        // Check for detail_pane view which only exists in the w673dp-h480dp layout variant
        val detailPane = findViewById<View>(com.difft.android.R.id.detail_pane)
        isDualPaneMode = detailPane != null

        if (isDualPaneMode) {
            // Empty state by default; restoreDetailFragmentsState() (posted from onCreate) reclaims
            // any recreation-restored detail fragments — too early to reclaim here.
            findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.VISIBLE
            findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.GONE

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
     * tapping it, tapping a rail tab, or pressing back — snaps it open again.
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
                            animateListPaneWidth(COLLAPSED_LIST_WIDTH_PX, frozenContentPx = binding.indexViewpager.layoutParams.width)
                        }
                    } else {
                        virtualWidthPx = overshoot
                        // Peek: reveal the edge of the (fully laid-out) list as feedback.
                        if (binding.indexViewpager.layoutParams.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                            binding.indexViewpager.layoutParams = binding.indexViewpager.layoutParams.apply {
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

        // Restore the collapsed back handler for a state persisted across recreation —
        // posted so registration lands AFTER onCreate's other back callbacks (see below).
        binding.root.post {
            if (DualPaneCollapseUtil.isCollapsed) ensureCollapsedBackCallback()
        }

        updateDividerAccessibility(divider)
    }

    /**
     * Register the back-to-expand handler LAZILY — at first collapse, or posted after
     * onCreate for a restored collapsed state: onCreate registers an always-enabled
     * moveTaskToBack callback AFTER setupDualPaneDivider runs, and OnBackPressedDispatcher
     * is LIFO — a handler registered before it would never receive Back.
     */
    private fun ensureCollapsedBackCallback() {
        if (collapsedBackCallback != null) {
            collapsedBackCallback?.isEnabled = true
            return
        }
        collapsedBackCallback = onBackPressedDispatcher.addCallback(this) {
            expandListPane()
        }.apply { isEnabled = true }
    }

    /** Collapse the list pane so the detail pane takes the full width. */
    private fun collapseListPane() {
        if (DualPaneCollapseUtil.isCollapsed) return
        DualPaneCollapseUtil.setCollapsed(true)
        ensureCollapsedBackCallback()
        animateListPaneWidth(COLLAPSED_LIST_WIDTH_PX)
        findViewById<DraggableDividerView>(com.difft.android.R.id.divider_pane)
            ?.let { updateDividerAccessibility(it) }
    }

    /** Expand the list pane back to its saved-ratio / default width. */
    private fun expandListPane(animate: Boolean = true) {
        if (!DualPaneCollapseUtil.isCollapsed) return
        DualPaneCollapseUtil.setCollapsed(false)
        collapsedBackCallback?.isEnabled = false
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
     * The pane content ([ActivityIndexBinding.indexViewpager]) is frozen at the wider endpoint
     * for the duration, so the list is revealed/concealed by the pane's clip instead of its
     * rows re-laying out on every frame (widths inside 1..min are never a layout state the
     * rows see); restored to MATCH_PARENT when the animation lands.
     */
    private fun animateListPaneWidth(targetPx: Int, frozenContentPx: Int = 0) {
        val listPane = findViewById<View>(com.difft.android.R.id.list_pane) ?: return
        val content = binding.indexViewpager
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
        binding.indexViewpager.importantForAccessibility = if (collapsed) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        (binding.indexViewpager as? android.view.ViewGroup)?.descendantFocusability = if (collapsed) {
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
        // Rail + rail divider widths come from the banded dual_pane_* dimens
        // (values/dimens.xml vs values-w900dp/dimens.xml), mirroring
        // layout-w673dp-h480dp/activity_index.xml. divider_pane is a floating overlay
        // (negative marginStart) and does NOT consume horizontal layout space.
        // WindowMetrics bounds INCLUDE system decoration, but the panes lay out inside the
        // root that BaseActivity's edge-to-edge pass pads by the horizontal insets — without
        // subtracting that padding the detail-pane floor is overstated by the inset (a side
        // navigation bar or landscape cutout) and the drag clamp can starve it below 360dp.
        val insetPaddingPx = binding.root.paddingLeft + binding.root.paddingRight
        val railPx = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_rail_width)
        val dividersPx = resources.getDimensionPixelSize(com.difft.android.R.dimen.dual_pane_divider_width)
        return (WindowSizeClassUtil.getWindowWidthPx(this) - insetPaddingPx - railPx - dividersPx)
            .coerceAtLeast(0)
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
    }

    // Per-tab tag (not per-conversation) — one detail fragment per tab, ceiling 3.
    private fun detailFragmentTagForTab(tabIndex: Int): String = "detail_tab_$tabIndex"
    private val detailFragmentTagRegex = Regex("""detail_tab_(\d+)""")

    /**
     * After a config-change recreation, FragmentManager auto-restores the detail fragments but our
     * [tabDetailFragments] map (an Activity field) is lost. Rebuild it from each fragment's per-tab
     * tag, then show the current tab's fragment and hide the rest — they share one container, so
     * without this they'd overlap (the bug #438 avoided by clearing instead).
     */
    private fun restoreDetailFragmentsState() {
        if (!isDualPaneMode) return

        // List-pane fragments to exclude (same whitelist as the old clear path).
        val viewPagerFragmentTypes = setOf(
            RecentChatFragment::class.java,
            ContactsFragment::class.java,
            MeFragment::class.java
        )

        // Restored detail fragments = non-list fragments with a per-tab tag (already attached, don't re-add).
        val restored = supportFragmentManager.fragments.filter { fragment ->
            !viewPagerFragmentTypes.contains(fragment.javaClass) &&
                fragment.tag?.let { detailFragmentTagRegex.matches(it) } == true
        }

        if (restored.isEmpty()) {
            // Cold start with nothing open: a persisted collapsed state over the empty detail
            // pane would be a blank first screen — silently un-collapse. On RECREATION the
            // user's collapse survives (the edge handle / tabs / back all lead out of it).
            if (!recreatedFromSavedState) expandListPane(animate = false)
            detailRestoreSettled = true
            return
        }

        restored.forEach { fragment ->
            val tabIndex = detailFragmentTagRegex.matchEntire(fragment.tag!!)!!.groupValues[1].toInt()
            tabDetailFragments[tabIndex] = fragment
        }

        currentTabIndex = binding.indexViewpager.currentItem

        // Show only the current tab's fragment, hide the rest (overlap fix).
        val transaction = supportFragmentManager.beginTransaction()
        tabDetailFragments.forEach { (tab, fragment) ->
            if (fragment != null) {
                if (tab == currentTabIndex) transaction.show(fragment) else transaction.hide(fragment)
            }
        }
        // Deferred post (viewpager.post) can dequeue after onSaveInstanceState during background
        // recreation; tolerate state loss (show/hide is re-derived on any later recreate).
        if (supportFragmentManager.isStateSaved) {
            L.w { "[IndexActivity] restoreDetailFragmentsState committing after state save (tolerated via commitAllowingStateLoss)" }
        }
        transaction.commitAllowingStateLoss()

        detailRestoreSettled = true

        // Restore current tab's chrome + currentConversationId (mirrors handleTabChangeForDualPane).
        val current = tabDetailFragments[currentTabIndex]
        if (current != null) {
            findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.GONE
            findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.VISIBLE
            currentConversationId = when (current) {
                is ChatFragment -> {
                    setDetailPaneChatBackground()
                    current.arguments?.getString(ChatFragment.ARG_CONTACT_ID)
                }
                is GroupChatFragment -> {
                    setDetailPaneChatBackground()
                    current.arguments?.getString(GroupChatFragment.ARG_GROUP_ID)
                }
                is ContactDetailFragment -> {
                    clearDetailPaneChatBackground()
                    current.arguments?.getString(ContactDetailFragment.ARG_CONTACT_ID)
                }
                else -> {
                    clearDetailPaneChatBackground()
                    null
                }
            }
        } else {
            findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.VISIBLE
            findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.GONE
            clearDetailPaneChatBackground()
            currentConversationId = null
        }

        L.i { "[IndexActivity] restoreDetailFragmentsState reclaimed=${tabDetailFragments.size} currentTab=$currentTabIndex hasDetail=${current != null}" }
        notifyListFragmentSelectionChanged()
    }

    /**
     * Show a one-on-one chat in the detail pane
     */
    private fun showChatInDetailPane(contactId: String, jumpMessageTimestamp: Long? = null) {
        if (!isDualPaneMode) return

        // Hide empty state
        findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.GONE
        findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.VISIBLE

        // Set chat background on detail_pane (not affected by IME padding in Fragment)
        setDetailPaneChatBackground()

        currentConversationId = contactId

        val newFragment = ChatFragment.newInstance(
            contactId = contactId,
            jumpMessageTimestamp = jumpMessageTimestamp
        )

        replaceDetailFragmentForCurrentTab(newFragment)
    }

    /**
     * Show a group chat in the detail pane
     */
    private fun showGroupChatInDetailPane(groupId: String, jumpMessageTimestamp: Long? = null) {
        if (!isDualPaneMode) return

        // Hide empty state
        findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.GONE
        findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.VISIBLE

        // Set chat background on detail_pane (not affected by IME padding in Fragment)
        setDetailPaneChatBackground()

        currentConversationId = groupId

        val newFragment = GroupChatFragment.newInstance(
            groupId = groupId,
            jumpMessageTimestamp = jumpMessageTimestamp
        )

        replaceDetailFragmentForCurrentTab(newFragment)
    }

    /**
     * Set chat background on detail_pane.
     * Background is set here (not in Fragment) so it stays fixed when keyboard appears.
     */
    private fun setDetailPaneChatBackground() {
        findViewById<View>(com.difft.android.R.id.detail_pane)?.background =
            ChatBackgroundDrawable(this)
    }

    /**
     * Clear chat background from detail_pane.
     */
    private fun clearDetailPaneChatBackground() {
        findViewById<View>(com.difft.android.R.id.detail_pane)?.background = null
    }

    /**
     * Handle tab change in dual-pane mode
     * Hide current tab's detail fragment and show the target tab's detail fragment
     */
    private fun handleTabChangeForDualPane(newTabIndex: Int) {
        if (!isDualPaneMode) return
        if (newTabIndex == currentTabIndex) return

        val oldFragment = tabDetailFragments[currentTabIndex]
        val newFragment = tabDetailFragments[newTabIndex]

        val transaction = supportFragmentManager.beginTransaction()

        // Hide old fragment
        oldFragment?.let { transaction.hide(it) }

        // Show or restore new fragment
        if (newFragment != null) {
            transaction.show(newFragment)
            // Hide empty state
            findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.GONE
            findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.VISIBLE
            // Update currentConversationId and background based on fragment type
            currentConversationId = when (newFragment) {
                is ChatFragment -> {
                    setDetailPaneChatBackground()
                    newFragment.arguments?.getString(ChatFragment.ARG_CONTACT_ID)
                }
                is GroupChatFragment -> {
                    setDetailPaneChatBackground()
                    newFragment.arguments?.getString(GroupChatFragment.ARG_GROUP_ID)
                }
                is ContactDetailFragment -> {
                    clearDetailPaneChatBackground()
                    newFragment.arguments?.getString(ContactDetailFragment.ARG_CONTACT_ID)
                }
                else -> {
                    clearDetailPaneChatBackground()
                    null
                }
            }
        } else {
            // No fragment for this tab, show empty state
            clearDetailPaneChatBackground()
            findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.VISIBLE
            findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.GONE
            currentConversationId = null
            // Guarded: the ViewPager2 restore dispatches a premature empty-tab pass before
            // the posted reclaim; expanding there would wipe a persisted collapse.
            if (detailRestoreSettled) expandListPane()
        }

        transaction.commit()
        currentTabIndex = newTabIndex

        // Notify the current tab's list fragment to update selection state
        notifyListFragmentSelectionChanged()
    }

    /**
     * Notify list fragments about selection change.
     * Finds ViewPager2 fragments and forwards the current selection.
     */
    private fun notifyListFragmentSelectionChanged() {
        if (!isDualPaneMode) return
        supportFragmentManager.fragments.forEach { fragment ->
            (fragment as? DualPaneSelectionListener)?.updateDualPaneSelection(currentConversationId)
        }
    }

    /**
     * Replace the detail fragment for current tab
     * This removes the old fragment (if any) and adds the new one
     */
    private fun replaceDetailFragmentForCurrentTab(newFragment: Fragment, tag: String? = null) {
        val oldFragment = tabDetailFragments[currentTabIndex]

        val transaction = supportFragmentManager.beginTransaction()

        // Remove old + add new => one detail fragment per tab (ceiling stays 3).
        oldFragment?.let { transaction.remove(it) }

        // Per-tab tag lets restoreDetailFragmentsState() reclaim this fragment after recreation.
        val effectiveTag = tag ?: detailFragmentTagForTab(currentTabIndex)
        transaction.add(com.difft.android.R.id.fragment_container_detail, newFragment, effectiveTag)
        transaction.commit()

        // Update the map
        tabDetailFragments[currentTabIndex] = newFragment
    }

    /**
     * Clear the detail pane and show empty state for current tab
     */
    private fun clearDetailPane() {
        if (!isDualPaneMode) return

        currentConversationId = null
        // A collapsed list over an empty detail pane would be a blank screen.
        expandListPane()

        // Remove fragment for current tab
        val oldFragment = tabDetailFragments[currentTabIndex]
        oldFragment?.let {
            supportFragmentManager.beginTransaction().remove(it).commit()
        }
        tabDetailFragments[currentTabIndex] = null

        // Clear chat background
        clearDetailPaneChatBackground()

        // Show empty state
        findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.VISIBLE
        findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.GONE
    }

    // ==================== ConversationNavigationCallback implementation ====================

    override fun onOneOnOneConversationSelected(contactId: String, jumpMessageTimestamp: Long?) {
        if (isDualPaneMode) {
            showChatInDetailPane(contactId, jumpMessageTimestamp)
        } else {
            // Fallback to Activity navigation for single-pane mode
            ChatActivity.startActivity(this, contactId, jumpMessageTimeStamp = jumpMessageTimestamp)
        }
    }

    override fun onGroupConversationSelected(groupId: String, jumpMessageTimestamp: Long?) {
        if (isDualPaneMode) {
            showGroupChatInDetailPane(groupId, jumpMessageTimestamp)
        } else {
            // Fallback to Activity navigation for single-pane mode
            GroupChatContentActivity.startActivity(this, groupId, jumpMessageTimestamp)
        }
    }

    override fun onContactDetailSelected(contactId: String) {
        if (isDualPaneMode) {
            showContactDetailInDetailPane(contactId)
        } else {
            // Fallback to Activity navigation for single-pane mode
            com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity.startActivity(this, contactId)
        }
    }

    /**
     * Show contact detail in the detail pane
     */
    private fun showContactDetailInDetailPane(contactId: String) {
        if (!isDualPaneMode) return

        // Hide empty state
        findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.GONE
        findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.VISIBLE

        // Clear chat background (contact detail doesn't use chat background)
        clearDetailPaneChatBackground()

        currentConversationId = contactId

        val newFragment = ContactDetailFragment.newInstance(contactId = contactId)

        replaceDetailFragmentForCurrentTab(newFragment)
    }

    // ==================== DualPaneHost implementation ====================

    /**
     * Show any fragment in the detail pane (generic method for all detail pages)
     * Used by settings pages, profile pages, etc.
     */
    override fun showDetailFragment(fragment: Fragment, tag: String?) {
        if (!isDualPaneMode) return

        // Hide empty state
        findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.GONE
        findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.VISIBLE

        // Clear chat background (generic fragments don't use chat background)
        clearDetailPaneChatBackground()

        // Clear current conversation id as this is not a conversation
        currentConversationId = null

        replaceDetailFragmentForCurrentTab(fragment, tag)
    }

    /**
     * Get ChatMessageListFragment from the detail pane in dual-pane mode
     * This is used by ConfidentialBottomSheetFragments to access the chat message list
     */
    override fun getChatMessageListFragment(): ChatMessageListFragment? {
        if (!isDualPaneMode) return null
        val detailFragment = supportFragmentManager.findFragmentById(com.difft.android.R.id.fragment_container_detail)
        return when (detailFragment) {
            is ChatFragment -> detailFragment.getChatMessageListFragment()
            is GroupChatFragment -> detailFragment.getChatMessageListFragment()
            else -> null
        }
    }

    // ==================== ChatInputFocusable implementation ====================

    override fun focusCurrentChatInputIfMatches(conversationId: String): Boolean {
        if (!isDualPaneMode || currentConversationId != conversationId) {
            return false
        }
        val detailFragment = supportFragmentManager.findFragmentById(com.difft.android.R.id.fragment_container_detail)
        (detailFragment as? ChatFragment)?.focusInputAndShowKeyboard()
        return true
    }
}
