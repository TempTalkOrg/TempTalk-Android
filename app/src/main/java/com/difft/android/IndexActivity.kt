package com.difft.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.call.util.FullScreenPermissionHelper
import com.difft.android.call.util.NetUtil
import com.difft.android.chat.R
import com.difft.android.chat.contacts.ContactsFragment
import com.difft.android.chat.contacts.contactsdetail.ContactDetailFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupChatFragment
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.recent.ConversationNavigationCallback
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
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.network.config.FeatureGrayManager
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.config.UserAgentManager
import com.difft.android.push.FcmInitResult
import com.difft.android.push.PushUtil
import com.difft.android.security.SecurityLib
import com.difft.android.setting.BackgroundConnectionSettingsActivity
import com.difft.android.setting.UpdateManager
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.cryptonew.EncryptionDataMigrationManager
import org.thoughtcrime.securesms.messages.FailedMessageProcessor
import org.thoughtcrime.securesms.messages.MessageForegroundService
import org.thoughtcrime.securesms.messages.MessageServiceManager
import org.thoughtcrime.securesms.messages.PendingMessageProcessor
import org.thoughtcrime.securesms.util.AppIconBadgeManager
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.websocket.WebSocketManager
import java.io.File
import javax.inject.Inject
import kotlin.system.exitProcess


@AndroidEntryPoint
class IndexActivity : BaseActivity(), ConversationNavigationCallback, ChatMessageListProvider, ChatInputFocusable {
    private lateinit var binding: ActivityIndexBinding

    // Dual-pane layout support for large screens
    // Using a marker view to detect dual-pane mode (w840dp layout)
    override var isDualPaneMode = false
        private set
    private var currentConversationId: String? = null

    // Store detail fragment for each tab (tab index -> Fragment)
    // This allows preserving fragment state when switching tabs
    private val tabDetailFragments = mutableMapOf<Int, Fragment?>()
    private var currentTabIndex = 0

    private val indicators by lazy {
        listOf(
            binding.indicatorviewChats,
            binding.indicatorviewContacts,
            binding.indicatorviewMe
        )
    }

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
    lateinit var encryptionDataMigrationManager: EncryptionDataMigrationManager

    @Inject
    lateinit var conversationSettingsManager: ConversationSettingsManager

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var messageServiceManager: MessageServiceManager

    @Inject
    lateinit var pushUtil: PushUtil

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIndexBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup dual-pane layout for large screens (w840dp)
        setupDualPaneLayout()

        // Load and emit text size early to avoid ANR in UI components
        TextSizeUtil.loadAndEmitTextSize()

        TextSizeUtil.textSizeState
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { textSize ->
                val isLarger = textSize == TextSizeUtil.TEXT_SIZE_LAGER
                indicators.forEach { it.updateSize(isLarger) }
            }
            .launchIn(lifecycleScope)

        binding.viewpager.apply {
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
                }
            }.also {
                // 初始化时手动触发一次，因为 OnPageChangeCallback 默认不会触发第一页
                it.onPageSelected(currentItem)
            })

            indicators.forEach {
                it.setOnClickListener { view ->
                    val index = indicators.indexOf(view)
                    if (index < 0) return@setOnClickListener

                    binding.viewpager.setCurrentItem(index, false)
                }
            }
        }

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

        initFirebaseCustomKey()

        startReceivingMessages()

        processPendingAndFailedMessages()

        WCDBUpdateService.start()

        cleanEmptyRooms()

        initFCMPush()

        observeFcmInitResult()

        checkEmulator()

        checkRoot()

        checkSign()

        registerUpgradeDownloadCompleteReceiver()

        checkUpdate()

        syncContactAndGroupInfo()

        setUserProfile()

        checkDisappearingMessage()

        startFileCleanupTask()

        observeAndUpdateUnreadMessageCountBadge()

        requestNotificationPermission()

        migrateEncryptionKeysIfNeeded()

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
                SharedPrefsUtil.putInt(SharedPrefsUtil.SP_UNREAD_MSG_NUM, unreadNotMuteMessageCount)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentData(intent)
    }

    private fun syncContactAndGroupInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            ContactorUtil.fetchAndSaveContactors(false)
            GroupUtil.syncAllGroupAndAllGroupMembers(this@IndexActivity, forceFetch = false, syncMembers = true)
        }
    }

    private fun recordUA() {
        L.i { "[UA] ======>" + UserAgentManager.getUserAgent() + "===uid:" + globalServices.myId }
    }


    private fun initFirebaseCustomKey() {
        Firebase.crashlytics.setCustomKey("uid", globalServices.myId.formatBase58Id())
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
//        ScreenShotUtil.refreshWithPagePolicy(this, binding.viewpager.currentItem != 0)
        checkInsiderUpdate()
        checkNotificationFullScreenPermission()
        checkNotificationPermission()
    }

    private fun checkDisappearingMessage() {
        messageArchiveManager.startCheckTask()
    }

    private fun startFileCleanupTask() {
        lifecycleScope.launch(Dispatchers.IO) {
            FileUtil.clearDraftAttachmentsDirectory()
            FileUtil.deleteMessageAttachmentEmptyDirectories()
        }
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
     * 校验apk签名
     */
    @SuppressLint("CheckResult")
    private fun checkSign() {
        if (!BuildConfig.DEBUG) {
            Single.create {
                val result = SecurityLib.checkApkSign(this)
                it.onSuccess(result)
            }
                .compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe { it ->
                    if (!it) {
                        ComposeDialogManager.showMessageDialog(
                            context = this@IndexActivity,
                            title = getString(R.string.app_sign_error_title),
                            message = getString(R.string.app_sign_error_tips),
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
                val pushCustomContent = com.google.gson.Gson().fromJson(
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
                    } else if (uri.host?.equals("group") == true) {
                        //chative://group/join?inviteCode=xHJ6Pw7n
                        val inviteCode = uri.getQueryParameter("inviteCode")
                        if (!TextUtils.isEmpty(inviteCode) && ValidatorUtil.isInviteCode(inviteCode.toString())) {
                            inviteUtils.joinGroupByInviteCode(inviteCode ?: "", this)
                        } else {
                            ToastUtil.showLong(R.string.invalid_link)
                        }
                    } else {
                        ToastUtil.showLong(R.string.invalid_link)
                    }
                } else if (uri.scheme?.equals("http") == true || uri.scheme?.equals("https") == true) {
                    val url = uri.toString()
                    this.openExternalBrowser(url)
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
        super.onDestroy()
        unregisterReceiver(upgradeDownloadCompleteReceiver)
    }

    private val upgradeDownloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                if (it.`package` == ApplicationHelper.instance.packageName) {
                    if (UpdateManager.ACTION_APK_DOWNLOAD_COMPLETED == intent.action) {
                        val status = intent.getIntExtra(UpdateManager.INTENT_PARAM_APK_DOWNLOAD_STATUS, -1)
                        val path = intent.getStringExtra(UpdateManager.INTENT_PARAM_APK_STORE_PATH) ?: ""
                        val isForce = intent.getBooleanExtra(UpdateManager.INTENT_PARAM_APK_FORCE_UPGRADE, false)
                        handleApkDownloadStatus(status, path, isForce)
                    }
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun registerUpgradeDownloadCompleteReceiver() {
        val filter = IntentFilter()
        filter.addAction(UpdateManager.ACTION_APK_DOWNLOAD_COMPLETED)
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        ContextCompat.registerReceiver(this, upgradeDownloadCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun handleApkDownloadStatus(status: Int, apkFilePath: String, isForce: Boolean = false) {
        when (status) {
            UpdateManager.STATUS_DOWNLOAD_SUCCESS -> {
                if (!TextUtils.isEmpty(apkFilePath)) {
                    val file = File(apkFilePath)
                    if (isForce) {
                        updateManager.closeForceUpdateDialog()
                        updateManager.showInstallDialog(this, file, true)
                    } else {
                        updateManager.showInstallDialog(this, file, false)
                    }
                } else {
                    ToastUtil.showLong(ResUtils.getString(com.difft.android.R.string.status_upgrade_install_failed))
                }
            }

            UpdateManager.STATUS_DOWNLOAD_FAILED -> {
                if (!TextUtils.isEmpty(apkFilePath) && File(apkFilePath).exists()) {
                    File(apkFilePath).delete()
                }
                ToastUtil.showLong(ResUtils.getString(com.difft.android.R.string.status_upgrade_downolad_failed))
            }

            UpdateManager.STATUS_VERIFY_FAILED -> {
                if (!TextUtils.isEmpty(apkFilePath) && File(apkFilePath).exists()) {
                    File(apkFilePath).delete()
                }
                ToastUtil.showLong(ResUtils.getString(com.difft.android.R.string.status_upgrade_verify_failed))
            }
        }
    }

    private fun setUserProfile() {
        loginRepo.setProfile()
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                L.i { "setUserProfile success" }
            }, { error ->
                L.w { "[IndexActivity] setUserProfile error: ${error.stackTraceToString()}" }
            })
    }

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    private var checkNotificationPermissionIgnore = false
    private var checkNotificationFullScreenPermissionIgnore = false
    private var checkNotificationPermissionDialog: ComposeDialog? = null
    private var checkNotificationFullScreenPermissionDialog: ComposeDialog? = null

    /**
     * 检查通知权限并显示引导对话框
     */
    private fun checkNotificationPermission() {
        // 1. 检查会话级别的忽略标志（防止同一会话重复弹出）
        if (checkNotificationPermissionIgnore) return

        // 2. 检查是否在当前版本已经取消过（每个版本只提示一次）
        val notificationPermissionCheckedVersion = userManager.getUserData()?.checkNotificationPermission
        if (notificationPermissionCheckedVersion == PackageUtil.getAppVersionName()) return

        // 3. 使用全面检查，包括权限和系统通知开关
        if (!messageNotificationUtil.canShowNotifications()) {
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
                        // 保存当前版本号，该版本不再提示
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
            // 通知可用，关闭引导对话框（如果正在显示）
            checkNotificationPermissionDialog?.dismiss()
            checkNotificationPermissionDialog = null
        }
    }

    private fun checkNotificationFullScreenPermission() {
        if (checkNotificationFullScreenPermissionIgnore) return

        if (!messageNotificationUtil.hasFullScreenNotificationPermission()) {
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
                logFile = file
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

        contentResolver.openInputStream(uri)?.use { inputStream ->
            file.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        return file
    }

    /**
     * 执行密钥迁移
     */
    private fun migrateEncryptionKeysIfNeeded() {
        lifecycleScope.launch(Dispatchers.IO) {
            encryptionDataMigrationManager.migrateIfNeeded()
        }
    }

    private fun initWCDB() {
        lifecycleScope.launch(Dispatchers.IO) {
            wcdb.tablesMap
        }
    }

    private fun fetchCallServiceUrlAndCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (NetUtil.checkNet(this@IndexActivity)) {
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
     * Setup dual-pane layout for large screens (width >= 840dp AND height >= 480dp)
     * Layout qualifier w840dp-h480dp ensures this layout only loads when both conditions are met.
     * Detects dual-pane mode by checking for detail_pane view which only exists in that layout.
     */
    private fun setupDualPaneLayout() {
        // Check for detail_pane view which only exists in the w840dp-h480dp layout variant
        val detailPane = findViewById<View>(com.difft.android.R.id.detail_pane)
        isDualPaneMode = detailPane != null

        if (isDualPaneMode) {
            // Clear any restored fragments from configuration change to prevent overlap
            // tabDetailFragments map is empty after recreation but FragmentManager may restore fragments
            clearRestoredDetailFragments()

            // Show empty state initially
            findViewById<View>(com.difft.android.R.id.empty_detail_view)?.visibility = View.VISIBLE
            findViewById<View>(com.difft.android.R.id.fragment_container_detail)?.visibility = View.GONE
        }
    }

    /**
     * Clear any fragments that were restored by FragmentManager after configuration change.
     * This prevents fragment overlap when tabDetailFragments map is empty but FragmentManager
     * has restored fragments from the previous configuration.
     */
    private fun clearRestoredDetailFragments() {
        // ViewPager fragments that should NOT be cleared
        val viewPagerFragmentTypes = setOf(
            RecentChatFragment::class.java,
            ContactsFragment::class.java,
            MeFragment::class.java
        )

        // Find all detail fragments (any fragment that is NOT a ViewPager fragment)
        val restoredFragments = supportFragmentManager.fragments.filter { fragment ->
            !viewPagerFragmentTypes.contains(fragment.javaClass)
        }

        if (restoredFragments.isNotEmpty()) {
            val transaction = supportFragmentManager.beginTransaction()
            restoredFragments.forEach { transaction.remove(it) }
            transaction.commitNow()
        }

        // Clear the map as well
        tabDetailFragments.clear()
        currentConversationId = null
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
                    newFragment.arguments?.getString("ARG_CONTACT_ID")
                }
                is GroupChatFragment -> {
                    setDetailPaneChatBackground()
                    newFragment.arguments?.getString("ARG_GROUP_ID")
                }
                is ContactDetailFragment -> {
                    clearDetailPaneChatBackground()
                    newFragment.arguments?.getString("ARG_CONTACT_ID")
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
        }

        transaction.commit()
        currentTabIndex = newTabIndex
    }

    /**
     * Replace the detail fragment for current tab
     * This removes the old fragment (if any) and adds the new one
     */
    private fun replaceDetailFragmentForCurrentTab(newFragment: Fragment, tag: String? = null) {
        val oldFragment = tabDetailFragments[currentTabIndex]

        val transaction = supportFragmentManager.beginTransaction()

        // Remove old fragment for current tab
        oldFragment?.let { transaction.remove(it) }

        // Add new fragment
        transaction.add(com.difft.android.R.id.fragment_container_detail, newFragment, tag)
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
