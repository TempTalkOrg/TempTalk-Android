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
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.MimeTypeMap
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
import com.difft.android.base.utils.DeeplinkUtils
import com.difft.android.base.utils.EnvironmentHelper
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
import com.difft.android.call.LCallManager
import com.difft.android.call.util.NetUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.ScreenShotUtil
import com.difft.android.chat.contacts.ContactsFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.recent.RecentChatFragment
import com.difft.android.chat.recent.RecentChatUtil
import com.difft.android.chat.recent.RecentChatViewModel
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.databinding.ActivityIndexBinding
import com.difft.android.login.PasscodeUtil
import com.difft.android.login.repo.LoginRepo
import com.difft.android.me.MeFragment
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.config.UserAgentManager
import com.difft.android.push.PushUtil
import com.difft.android.security.SecurityLib
import com.difft.android.setting.NotificationSettingsActivity
import com.difft.android.setting.UpdateManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.util.FullScreenPermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.cryptonew.EncryptionDataMigrationManager
import org.thoughtcrime.securesms.messages.FailedMessageProcessor
import org.thoughtcrime.securesms.messages.MessageForegroundService
import org.thoughtcrime.securesms.util.AppIconBadgeManager
import org.thoughtcrime.securesms.util.ForegroundServiceUtil
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.util.UnableToStartException
import org.thoughtcrime.securesms.util.WindowUtil
import org.thoughtcrime.securesms.websocket.WebSocketManager
import util.concurrent.TTExecutors
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.system.exitProcess


@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class IndexActivity : BaseActivity() {
    private lateinit var binding: ActivityIndexBinding

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
    lateinit var webSocketManager: WebSocketManager

    @Inject
    lateinit var encryptionDataMigrationManager: EncryptionDataMigrationManager

    @Inject
    lateinit var conversationSettingsManager: ConversationSettingsManager

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowUtil.setNavigationBarColor(this, ContextCompat.getColor(this, com.difft.android.base.R.color.bottom_tab_bg))
        binding = ActivityIndexBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewpager.apply {
            offscreenPageLimit = 1
            isUserInputEnabled = false
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

            ScreenShotUtil.setScreenShotEnable(this@IndexActivity, false)

            registerOnPageChangeCallback(object : OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    if (position == 0) {
                        ScreenShotUtil.setScreenShotEnable(this@IndexActivity, false)
                    } else {
                        ScreenShotUtil.setScreenShotEnable(this@IndexActivity, true)
                    }
                    selectIndicator(position)
                }
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

        DeeplinkUtils.deeplink
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ linkData ->
                handleDeeplink(linkData)
            }, { error ->
                error.printStackTrace()
            })
        handleShareIntent(intent)
        recordUA()

        initWCDB()

        startReceivingMessages()

        processFailedMessages()

        WCDBUpdateService.start()

        initFCMPush()

        checkEmulator()

        checkRoot()

        checkSign()

        registerUpgradeDownloadCompleteReceiver()

        checkUpdate()

        syncContactAndGroupInfo()

        setUserProfile()

        checkDisappearingMessage()

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

        TextSizeUtil.textSizeChange
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                indicators.forEach {
                    it.updateSize()
                }
            }, { it.printStackTrace() })

        fetchCallServiceUrlAndCache()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerPermission { permissionState ->
                L.i { "[Notification] requestNotificationPermission permissionState:$permissionState" }
                checkNotificationPermission()
            }.launchSinglePermission(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun observeAndUpdateUnreadMessageCountBadge() {
        recentChatViewModel.allRecentRoomsStateFlow.onEach {
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
//            if (unreadNotMuteMessageCount == 0) { //if there is no unread message, cancel all notifications, let the app's icon's red point badge disappear
//                L.i { "[Call] indexActivity cancelAllNotifications" }
//                if (!LCallManager.hasCallDataNotifying()) {
//                    messageNotificationUtil.cancelAllNotifications()
//                }
//            }
        }.launchIn(lifecycleScope)
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
        L.i { "onNewIntent - indexActivity" }
        handleShareIntent(intent)
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

    private fun startReceivingMessages() {
        webSocketManager.start()
    }

    private fun processFailedMessages() {
        try {
            lifecycleScope.launch {
                failedMessageProcessor.processFailedMessages()
            }
        } catch (e: Exception) {
            L.e { "[FailedMessageProcessor] Error processing failed messages: ${e.stackTraceToString()}" }
        }
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
        Observable.timer(2000, TimeUnit.MILLISECONDS)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this@IndexActivity))
            .subscribe({
                updateManager.checkUpdate(this@IndexActivity, false)
            }, {})
    }

    private var insiderUpdateChecked = false

    //Insider版本每次回到前台，超过三十分钟会自动检查更新
    private fun checkInsiderUpdate() {
        if (!environmentHelper.isInsiderChannel()) return
        val lastCheckUpdateTime = userManager.getUserData()?.lastCheckUpdateTime ?: 0
        if (!insiderUpdateChecked || (System.currentTimeMillis() - lastCheckUpdateTime > 30 * 60 * 1000)) {
            Observable.timer(2000, TimeUnit.MILLISECONDS)
                .compose(RxUtil.getSchedulerComposer())
                .to(RxUtil.autoDispose(this@IndexActivity))
                .subscribe({
                    updateManager.checkUpdate(this@IndexActivity, false)
                }, {})
            insiderUpdateChecked = true
        }
    }

    private fun initFCMPush() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
            if (!task.isSuccessful) {
                L.w { "[fcm] Fetching FCM registration token failed " + task.exception }
                // FCM获取失败，检查是否需要启动前台服务
                checkAndStartMessageForegroundService()
                return@OnCompleteListener
            }

            // Get new FCM registration token
            val token = task.result
            L.i { "[fcm] Fetching FCM registration token success ${token.length}" }
            PushUtil.sendRegistrationToServer(null, token) {
                // 服务器注册完成后，检查是否需要启动前台服务
                checkAndStartMessageForegroundService()
            }
        })
        FirebaseMessaging.getInstance().setDeliveryMetricsExportToBigQuery(true)
    }

    /**
     * 检查是否需要开启消息前台服务，google service不可用，或者fcm没有注册成功过
     */
    private fun checkAndStartMessageForegroundService() {
        lifecycleScope.launch(Dispatchers.IO) {
            val fcmEnable = userManager.getUserData()?.fcmEnable
            val autoStartMessageService = userManager.getUserData()?.autoStartMessageService
            val playServiceStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this@IndexActivity)

            withContext(Dispatchers.Main) {
                L.i { "[MessageForegroundService] checkAndStartMessageForegroundService, playServiceStatus:$playServiceStatus, fcmEnable:$fcmEnable, autoStartMessageService:$autoStartMessageService" }

                if (playServiceStatus == ConnectionResult.SUCCESS && fcmEnable == true) {
                    ForegroundServiceUtil.stopService(MessageForegroundService::class.java)
                    return@withContext
                }

                //设置过不自动启动服务时进行提示
                if (autoStartMessageService != true) {
                    showStartMessageServiceTipsDialog()
                    return@withContext
                }

                try {
                    ForegroundServiceUtil.start(this@IndexActivity, Intent(this@IndexActivity, MessageForegroundService::class.java))
                } catch (e: UnableToStartException) {
                    L.w { "[MessageForegroundService] Unable to start foreground service for websocket. Deferring to background to try with blocking" }
                    TTExecutors.UNBOUNDED.execute {
                        try {
                            ForegroundServiceUtil.startWhenCapable(this@IndexActivity, Intent(this@IndexActivity, MessageForegroundService::class.java))
                        } catch (e: UnableToStartException) {
                            L.w { "[MessageForegroundService] Unable to start foreground service for websocket!" + e.stackTraceToString() }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkInsiderUpdate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkNotificationFullScreenPermission()
            checkNotificationPermission()
            checkIgnoreBatteryOptimizations()
        }
    }

    private fun checkDisappearingMessage() {
        messageArchiveManager.startCheckTask()
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
     * 无google service时，提示用户去设置后台连接服务
     * 每个版本只提示一次
     */
    private fun showStartMessageServiceTipsDialog() {
        val messageServiceTipsShowedVersion = userManager.getUserData()?.messageServiceTipsShowedVersion
        if (messageServiceTipsShowedVersion == PackageUtil.getAppVersionName()) return
        ComposeDialogManager.showMessageDialog(
            context = this@IndexActivity,
            title = getString(R.string.tip),
            message = getString(R.string.notification_no_google_tip),
            confirmText = getString(R.string.notification_go_to_settings),
            cancelText = getString(R.string.notification_ignore),
            cancelable = false,
            onConfirm = {
                NotificationSettingsActivity.startActivity(this@IndexActivity)
            }
        )
        userManager.update {
            this.messageServiceTipsShowedVersion = PackageUtil.getAppVersionName()
        }
    }

    private fun handleDeeplink(linkData: LinkDataEntity) {
        L.i { "[Deeplink] Handle deeplink:${linkData.category} linkData:$linkData" }
        when (linkData.category) {
            LinkDataEntity.CATEGORY_PUSH, LinkDataEntity.CATEGORY_MESSAGE -> {
                PasscodeUtil.disableScreenLock = false
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
                L.i { "BH_Lin: setUserProfile success" }
            }, { error ->
                error.printStackTrace()
            })
    }

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    private var checkNotificationPermissionIgnore = false
    private var checkNotificationFullScreenPermissionIgnore = false
    private var checkNotificationPermissionDialog: ComposeDialog? = null
    private var checkNotificationFullScreenPermissionDialog: ComposeDialog? = null
    private var checkIgnoreBatteryOptimizationsDialog: ComposeDialog? = null

    /**
     * 每次更新版本需要进行检查提醒
     */
    private fun checkNotificationPermission() {
        if (checkNotificationPermissionIgnore) return

        if (!messageNotificationUtil.hasNotificationPermission()) {
            if (checkNotificationPermissionDialog == null) {
                checkNotificationPermissionDialog = ComposeDialogManager.showMessageDialog(
                    context = this@IndexActivity,
                    title = getString(R.string.tip),
                    message = getString(R.string.notification_no_permission_tip1, PackageUtil.getAppName()),
                    confirmText = getString(R.string.notification_go_to_settings),
                    cancelText = getString(R.string.notification_ignore),
                    onConfirm = {
                        messageNotificationUtil.openNotificationSettings(this@IndexActivity)
                    },
                    onCancel = {
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

    private fun getFullScreenPermissionMessage(): String {
        return if (FullScreenPermissionHelper.isMainStreamChinaMobile()) {
            when (Build.MANUFACTURER.lowercase(Locale.ROOT)){
                FullScreenPermissionHelper.MANUFACTURER_HUAWEI.lowercase(Locale.ROOT) -> {
                    getString(R.string.notification_no_permission_tip4, PackageUtil.getAppName())
                }
                FullScreenPermissionHelper.MANUFACTURER_XIAOMI.lowercase(Locale.ROOT) -> {
                    getString(R.string.notification_no_permission_tip5, PackageUtil.getAppName())
                }
                FullScreenPermissionHelper.MANUFACTURER_HONOR.lowercase(Locale.ROOT) -> {
                    getString(R.string.notification_no_permission_tip6, PackageUtil.getAppName())
                }
                else -> getString(R.string.notification_no_permission_tip4, PackageUtil.getAppName())
            }
        } else {
            getString(R.string.notification_no_permission_tip2, PackageUtil.getAppName())
        }
    }

    private fun checkNotificationFullScreenPermission() {
        if (checkNotificationFullScreenPermissionIgnore) return

        if (!messageNotificationUtil.hasFullScreenNotificationPermission()) {
            if (checkNotificationFullScreenPermissionDialog == null) {
                val message = getFullScreenPermissionMessage()
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

    private fun checkIgnoreBatteryOptimizations() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {

                val checkIgnoreBatteryOptimizations = userManager.getUserData()?.checkIgnoreBatteryOptimizations
                if (checkIgnoreBatteryOptimizations == PackageUtil.getAppVersionName()) return

                if (checkIgnoreBatteryOptimizationsDialog == null) {
                    checkIgnoreBatteryOptimizationsDialog = ComposeDialogManager.showMessageDialog(
                        context = this@IndexActivity,
                        title = getString(R.string.tip),
                        message = getString(R.string.battery_optimizations_tips, PackageUtil.getAppName()),
                        confirmText = getString(R.string.notification_go_to_settings),
                        cancelText = getString(R.string.notification_ignore),
                        onConfirm = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        },
                        onCancel = {
                            userManager.update {
                                this.checkIgnoreBatteryOptimizations = PackageUtil.getAppVersionName()
                            }
                        },
                        onDismiss = {
                            checkIgnoreBatteryOptimizationsDialog = null
                        }
                    )
                }
            }
        } catch (e: Exception) {
            L.e { "checkAndRequestIgnoreBatteryOptimizations error: ${e.stackTraceToString()}" }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            try {
                when (intent.type) {
                    "text/plain" -> {
                        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                        sharedText?.let {
                            // Do something with the shared text
                            selectChatsUtils.showChatSelectAndSendDialog(
                                this@IndexActivity,
                                sharedText,
                            )
                        }
                    }

                    else -> {
                        // Get URI from ClipData first
                        val uri = getUriFromIntent(intent)
                        
                        if (uri == null) {
                            val extraText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                            selectChatsUtils.showChatSelectAndSendDialog(
                                this,
                                extraText,
                            )
                        } else {
                            // Handle shared content URI (image, file, etc.)
                            selectChatsUtils.showChatSelectAndSendDialog(
                                this,
                                "",
                                logFile = copyUriToFile(uri)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                L.e { "SharedContent Received Exception: ${e.message}" }
                e.printStackTrace()
            }
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
            if(NetUtil.checkNet(this@IndexActivity)){
                LCallManager.fetchCallServiceUrlAndCache()
            }
        }
    }
}
