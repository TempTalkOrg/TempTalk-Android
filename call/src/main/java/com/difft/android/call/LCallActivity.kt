package com.difft.android.call

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.common.ScreenshotDetector
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.CountdownTimer
import com.difft.android.base.user.PromptReminder
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.user.defaultBarrageTexts
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.DialogActionType
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.ui.CallContent
import com.difft.android.call.manager.ProximitySensorManager
import com.difft.android.call.manager.CallDialogManager
import com.difft.android.call.manager.PictureInPictureManager
import com.difft.android.call.handler.CallErrorHandler
import com.difft.android.call.handler.CallExitHandler
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.handler.CallActionHandler
import com.difft.android.call.handler.InviteRequestState
import com.difft.android.call.manager.CallLifecycleObserver
import com.difft.android.call.manager.CallServiceManager
import com.difft.android.call.manager.CallCleanupManager
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.manager.CallVibrationManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.receiver.CallActivityBroadcastReceiver
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver
import com.difft.android.call.ui.InviteViewState
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.call.util.ScreenDeviceUtil
import com.difft.android.network.config.GlobalConfigsManager
import org.difft.android.libraries.denoise_filter.DenoisePluginAudioProcessor
import dagger.hilt.android.AndroidEntryPoint
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


/**
 * 通话 Activity
 * 
 * 负责管理通话界面的生命周期、UI 交互和资源管理。
 * 通过委托模式将业务逻辑分散到各个 Manager 类中，保持代码清晰和可维护性。
 * 
 * 主要职责：
 * - 管理 Activity 生命周期
 * - 初始化和管理各种 Manager（错误处理、退出处理、服务管理等）
 * - 处理用户交互事件（点击、返回键等）
 * - 管理 UI 状态和 Compose 组件
 * - 协调各个 Manager 之间的交互
 */
@AndroidEntryPoint
class LCallActivity : AppCompatActivity() {

    @Inject
    lateinit var callToChatController: LCallToChatController

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var callDataManagerLazy: Lazy<CallDataManager>

    @Inject
    lateinit var vibrationManager: CallVibrationManager

    @Inject
    lateinit var ringtoneManager: CallRingtoneManager

    @Inject
    lateinit var contactorCacheManager: ContactorCacheManager

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    private lateinit var callIntent: CallIntent

    private val callRole: CallRole by lazy {
        if (callIntent.callRole == CallRole.CALLER.type) CallRole.CALLER else CallRole.CALLEE
    }

    private val callConfig: CallConfig by lazy {
        globalConfigsManager.getNewGlobalConfigs()?.data?.call ?: CallConfig(autoLeave = AutoLeave(promptReminder = PromptReminder()), chatPresets = defaultBarrageTexts, chat = CallChat(), countdownTimer = CountdownTimer())
    }

    private val autoHideTimeout: Long by lazy {
        callConfig.chat?.autoHideTimeout ?: CallChat().autoHideTimeout
    }

    private val muteOtherEnabled: Boolean by lazy {
        callConfig.muteOtherEnabled
    }

    private val audioProcessor = DenoisePluginAudioProcessor()

    private var pictureInPictureManager: PictureInPictureManager? = null

    private var proximitySensorManager: ProximitySensorManager? = null

    private var screenshotDetector: ScreenshotDetector? = null

    private var callErrorHandler: CallErrorHandler? = null

    private var callExitHandler: CallExitHandler? = null

    private var callActionHandler: CallActionHandler? = null

    private var callLifecycleObserver: CallLifecycleObserver? = null

    private lateinit var backPressedCallback: OnBackPressedCallback

    private var callDialogManager: CallDialogManager? = null

    private var inviteCallManager: InviteCallHandler? = null

    private var callServiceManager: CallServiceManager? = null

    private var callCleanupManager: CallCleanupManager? = null

    private var callActivityBroadcastReceiver: CallActivityBroadcastReceiver? = null

    private var screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver? = null

    private lateinit var appUnlockListener: (Boolean) -> Unit

    private val callbackId = "LCallActivity_${System.identityHashCode(this)}"

    private val viewModel: LCallViewModel by viewModelByFactory {
        LCallViewModel(
            e2eeEnable = true,
            application = application,
            callIntent = callIntent,
            callConfig = callConfig,
            callRole = callRole,
            audioProcessor = audioProcessor
        )
    }


    /**
     * Activity 创建时的初始化
     *
     * @param savedInstanceState 保存的实例状态，如果 Activity 被重建则不为 null
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        L.i { "[Call] LCallActivity: onCreate" }
        super.onCreate(savedInstanceState)
        // 占位 content，确保 ViewTreeLifecycleOwner 已设置，避免 initializeView 中 CallWaitDialogUtil
        //（ComposeDialogManager 将 ComposeView 加入 content）时出现 ViewTreeLifecycleOwner not found crash
        setContentView(android.widget.FrameLayout(this))

        // 1. Intent 处理
        handleIntent(savedInstanceState)

        // 2. 初始化错误处理器
        initializeErrorHandler()

        // 3. 初始化退出处理器
        initializeExitHandler()

        // 3.5. 初始化动作处理器
        initializeCallActionHandler()

        // 4. 状态初始化
        initializeState()

        // 4. UI 初始化
        initializeView()

        // 5. 管理器初始化
        initializeManagers()

        // 5.5. 初始化生命周期观察者
        initializeLifecycleObserver()

        // 5.6. 初始化清理管理器
        initializeCleanupManager()

        // 6. 监听器注册
        registerListeners()

        // 7. 窗口设置
        configureWindow()
    }

    /**
     * 初始化通话状态
     */
    private fun initializeState() {
        // 仅在非宽屏设备上强制竖屏，宽屏设备允许自由旋转以撑满全屏
        if (!WindowSizeClassUtil.shouldUseDualPaneLayout(this)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onGoingCallStateManager.setIsInCalling(true)

        if (isAppLockEnabled()) {
            onGoingCallStateManager.setNeedAppLock(callIntent.needAppLock)
        } else {
            onGoingCallStateManager.setNeedAppLock(false)
        }

        // 不再在此处统一 dismiss：来自 joinCall/startCall 时由 setContent 前 dismiss，其他入口无弹窗

        viewModel.getRoomId()?.let {
            onGoingCallStateManager.setCurrentRoomId(it)
        }

        viewModel.callUiController.setPipModeEnabled(isInPictureInPictureMode)
        onGoingCallStateManager.setIsInPipMode(isInPictureInPictureMode)
    }

    /**
     * 初始化错误处理器
     * 
     * 创建 [CallErrorHandler] 实例，用于统一处理通话过程中的错误。
     * 错误处理器会在发生错误时显示错误提示，并在必要时结束通话。
     */
    private fun initializeErrorHandler() {
        callErrorHandler = CallErrorHandler(
            activity = this,
            lifecycleScope = lifecycleScope,
            callIntent = callIntent,
            onEndCall = { endCallAndClearResources() },
            onDismissError = { viewModel.dismissError() }
        )
    }

    /**
     * 初始化退出处理器
     * 
     * 创建 [CallExitHandler] 实例，用于统一处理退出通话的逻辑。
     * 退出处理器会根据不同的退出类型（离开、结束等）执行相应的清理操作。
     */
    private fun initializeExitHandler() {
        callExitHandler = CallExitHandler(
            viewModel = viewModel,
            callToChatController = callToChatController,
            onGoingCallStateManager = onGoingCallStateManager,
            callDataManager = callDataManager,
            callIntent = callIntent,
            callRole = callRole,
            conversationId = onGoingCallStateManager.getConversationId(),
            callType = onGoingCallStateManager.callType().ifEmpty { callIntent.callType },
            onEndCall = { endCallAndClearResources() }
        )
    }

    /**
     * 初始化通话动作处理器
     * 
     * 创建 [CallActionHandler] 实例，用于统一处理通话控制动作（接听、拒绝、挂断等）。
     * 动作处理器会处理来自广播接收器或其他来源的通话控制请求。
     */
    private fun initializeCallActionHandler() {
        callActionHandler = CallActionHandler(
            viewModel = viewModel,
            onGoingCallStateManager = onGoingCallStateManager,
            callRingtoneManager = ringtoneManager,
            callIntent = callIntent,
            callRole = callRole,
            conversationId = onGoingCallStateManager.getConversationId(),
            onExitClick = { params -> handleExitClick(params) },
            onEndCall = { endCallAndClearResources() },
            onShowTip = { message, onDismiss -> showStyledPopTip(message, onDismiss) }
        )
    }

    /**
     * 处理传入的 Intent
     * 
     * 解析 Intent 并初始化通话参数。仅在 Activity 首次创建时处理 Intent，
     * 如果 Activity 因配置变更（如屏幕旋转）而重建，则跳过处理以避免重复初始化。
     * 
     * @param savedInstanceState 保存的实例状态，用于判断是否为首次创建
     */
    private fun handleIntent(savedInstanceState: Bundle?) {
        callIntent = getCallIntent()
        if (savedInstanceState == null) {
            L.i { "[Call] LCallActivity: Processing intent" }
            if (callRole == CallRole.CALLEE) {
                LCallManager.stopIncomingCallService(callIntent.roomId, tag = "accept: has in call activity")
            } else {
                if (callIntent.action == CallIntent.Action.START_CALL && callIntent.callType == CallType.ONE_ON_ONE.type) {
                    callIntent.conversationId?.let { conversationId ->
                        viewModel.addAwaitingJoinInvitees(listOf(conversationId))
                    }
                }
            }
            logIntent(callIntent)
            processIntent(callIntent)
        } else {
            L.i { "[Call] LCallActivity: Activity likely rotated, not processing intent" }
        }
    }

    /**
     * 初始化各种管理器
     * 
     * 按顺序初始化以下管理器：
     * - 服务管理器：管理前台服务
     * - 画中画管理器：管理 PIP 模式
     * - 距离传感器管理器：检测手机是否靠近脸部
     * - 对话框管理器：统一管理对话框显示
     * - 邀请管理器：管理邀请用户加入通话
     */
    private fun initializeManagers() {
        initializeCallServiceManager()
        initializePictureInPictureManager()
        initializeProximitySensor()
        initializeDialogManager()
        initializeInviteCallManager()
    }

    /**
     * 注册各种监听器
     * 
     * 注册以下监听器：
     * - 通话广播接收器：接收通话相关的广播事件
     * - 返回键处理：拦截返回键并尝试进入 PIP 模式
     * - 应用锁监听器：监听应用锁状态变化
     * - 屏幕解锁广播接收器：监听屏幕解锁事件
     */
    private fun registerListeners() {
        registerCallActivityReceiver()
        registerOnBackPressedHandler()
        registerAppUnlockListener()
        registerScreenUnlockReceiver()
    }

    /**
     * 配置窗口属性
     * 
     * 设置以下窗口属性：
     * - 保持屏幕常亮
     * - 允许在锁屏界面显示
     * - 解锁时自动点亮屏幕
     * - 键盘不调整窗口大小（由 Compose 的 imePadding 处理）
     */
    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Don't let keyboard resize the Activity content - we handle IME insets manually in Compose
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        allowOnLockScreen()
    }

    /**
     * 从 Intent 中解析通话 Intent
     * 
     * @return 解析后的 [CallIntent] 对象
     */
    private fun getCallIntent(): CallIntent {
        return CallIntent(intent)
    }

    /**
     * 处理通话 Intent
     * 
     * 根据 Intent 的 action 类型处理不同的通话场景：
     * - START_CALL：发起通话
     * - JOIN_CALL：加入通话
     * 
     * 设置通话类型和会话 ID。
     * 
     * @param callIntent 解析后的通话 Intent
     */
    private fun processIntent(callIntent: CallIntent) {
        if (callIntent.action == CallIntent.Action.START_CALL || callIntent.action == CallIntent.Action.JOIN_CALL) {
            if (onGoingCallStateManager.callType().isEmpty()) {
                onGoingCallStateManager.setCallType(callIntent.callType)
            }
            onGoingCallStateManager.setConversationId(callIntent.conversationId)
        }
    }

    /**
     * 记录 Intent 信息到日志
     * 
     * @param callIntent 要记录的通话 Intent
     */
    private fun logIntent(callIntent: CallIntent) {
        L.d { "[Call] LCallActivity logIntent:$callIntent" }
    }

    /**
     * 初始化 UI
     * 
     * 设置 Compose UI 内容，包括：
     * - 通话房间信息
     * - 屏幕分享状态
     * - 各种回调处理（点击、邀请、退出等）
     * 
     * 注意：前台服务的启动已移至 [initializeCallServiceManager] 中。
     */
    private fun initializeView() {
        L.i { "[Call] LCallActivity initView" }
        if (!callIntent.callWaitDialogShown) {
            CallWaitDialogUtil.show(this)
        }
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                viewModel.room
            }
            withContext(Dispatchers.Main) {
                CallWaitDialogUtil.dismiss()
                setContent {
                    val room = viewModel.room
                    val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
                    CallContent(
                        room = room,
                        viewModel = viewModel,
                        audioSwitchHandler = viewModel.audioHandler,
                        inviteCallHandler = inviteCallManager,
                        isUserSharingScreen = isUserSharingScreen,
                        callConfig = callConfig,
                        callIntent = callIntent,
                        callRole = callRole,
                        conversationId = onGoingCallStateManager.getConversationId(),
                        autoHideTimeout = autoHideTimeout,
                        muteOtherEnabled = muteOtherEnabled,
                        audioProcessor = audioProcessor,
                        onScreenClick = { handleScreenClick() },
                        onCallTypeChanged = {
                            onGoingCallStateManager.setCallType(it)
                            updateScreenshotListeningState()
                        },
                        onInviteUsersClick = { handleInviteUsersClick() },
                        onWindowZoomOutClick = { handleWindowZoomOutClick() },
                        onInviteViewAction = { handleInviteViewAction(it)},
                        onExitClick = { params, callEndType -> handleExitClick(params, callEndType) },
                        onBottomCallEndAction = { action -> handleBottomCallEndAction(action) }
                    )
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())


    /**
     * 处理屏幕点击事件
     * 
     * 根据当前 UI 状态切换显示：
     * - 如果显示简单弹幕且底部工具栏可见，则隐藏弹幕
     * - 否则切换顶部状态栏和底部工具栏的显示状态
     */
    private fun handleScreenClick() {
        val showBottomToolBarViewEnabled = viewModel.callUiController.showBottomToolBarViewEnabled.value
        if (viewModel.callUiController.showSimpleBarrageEnabled.value && showBottomToolBarViewEnabled) {
            viewModel.callUiController.setShowSimpleBarrageEnabled(false)
        } else {
            val showTopStatusViewEnabled = viewModel.callUiController.showTopStatusViewEnabled.value
            viewModel.callUiController.setShowTopStatusViewEnabled(!showTopStatusViewEnabled)
            viewModel.callUiController.setShowBottomToolBarViewEnabled(!showBottomToolBarViewEnabled)
        }
    }


    companion object {
        const val ACTION_IN_CALLING_CONTROL = "ACTION_IN_CALLING_CONTROL"
        const val EXTRA_CONTROL_TYPE = "EXTRA_CONTROL_TYPE"
        const val EXTRA_PARAM_ROOM_ID = "EXTRA_PARAM_ROOM_ID"
        private const val DEFAULT_FONT_SCALE = 1.0f
    }

    /**
     * 用户离开 Activity 时的回调
     * 
     * 当用户按下 Home 键或切换到其他应用时触发。
     * 如果当前没有显示权限对话框，则尝试进入 PIP 模式或显示权限提示。
     */
    @SuppressLint("MissingSuperCall")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        L.i { "[Call] LCallActivity onUserLeaveHint" }
        if (viewModel.isRequestingPermission()) {
            L.i { "[Call] LCallActivity onUserLeaveHint ignored (permission dialog showing)" }
            return
        }

        showPipPermissionToastOrEnterPipMode("onUserLeaveHint")
    }

    /**
     * Activity 销毁时的清理
     * 
     * 委托给 [CallCleanupManager] 执行所有资源清理操作，包括：
     * - 移除生命周期观察者
     * - 清理 UI 资源（对话框、返回键回调）
     * - 释放管理器资源（传感器、PIP、广播接收器）
     * - 停止服务和清理系统资源
     * - 重置状态和清理监听器
     * 
     * 最后清理所有引用，防止内存泄漏。
     */
    override fun onDestroy() {
        super.onDestroy()
        L.i { "[Call] LCallActivity onDestroy start." }
        
        // 执行统一清理
        callCleanupManager?.cleanup(
            lifecycleObserver = callLifecycleObserver,
            dialogManager = callDialogManager,
            handler = handler,
            proximitySensorManager = proximitySensorManager,
            pictureInPictureManager = pictureInPictureManager,
            callActivityBroadcastReceiver = callActivityBroadcastReceiver,
            screenUnlockBroadcastReceiver = screenUnlockBroadcastReceiver,
            serviceManager = callServiceManager,
            onGoingCallStateManager = onGoingCallStateManager,
            callDataManager = callDataManager,
            ringtoneManager = ringtoneManager,
            vibrationManager = vibrationManager,
            contactorCacheManager = contactorCacheManager,
            callControlMessageManager = onGoingCallStateManager,
            audioProcessor = audioProcessor,
            viewModel = viewModel,
            backPressedCallback = if (::backPressedCallback.isInitialized) backPressedCallback else null
        )

        // 清理引用，防止内存泄漏
        callLifecycleObserver = null
        callDialogManager = null
        proximitySensorManager = null
        pictureInPictureManager = null
        callActivityBroadcastReceiver = null
        screenUnlockBroadcastReceiver = null
        callServiceManager = null
        callCleanupManager = null
        screenshotDetector?.release()
        screenshotDetector = null

        L.i { "[Call] LCallActivity onDestroy end." }
    }


    /**
     * 结束通话并清理资源
     * 
     * 设置退出状态，调用 ViewModel 的清理方法，然后立即关闭 Activity。
     * ViewModel 的清理操作（包括重 I/O 操作）已在 viewModelScope 中执行。
     * 
     * 注意：此方法会立即关闭 Activity，不会等待清理完成。
     */
    private fun endCallAndClearResources() {
        // 设置退出状态（MutableStateFlow 是线程安全的）
        onGoingCallStateManager.setIsInCallEnding(true)
        
        runOnUiThread {
            // 调用 ViewModel 的清理方法，重 I/O 操作已在 viewModelScope 中执行
            viewModel.doExitClear()
            // 立即 finish Activity
            finishAndRemoveTask()
        }
    }

    /**
     * 获取资源对象
     * 
     * 重写此方法以适配字体缩放问题。系统字体缩放会导致布局错乱，
     * 因此强制重置字体缩放为默认值 1.0f。
     * 
     * @return 重置字体缩放后的资源对象
     */
    override fun getResources(): Resources {
        val res = super.getResources()
        // 适配字体缩放问题，字体缩放会导致布局错乱，此处重置为默认值1.0f
        if (res.configuration.fontScale != DEFAULT_FONT_SCALE) {
            val newConfig = Configuration(res.configuration)
            newConfig.fontScale = DEFAULT_FONT_SCALE
            res.updateConfiguration(newConfig, res.displayMetrics)
        }
        return res
    }

    /**
     * PIP 模式状态变化回调
     * 
     * 当 Activity 进入或退出 PIP 模式时调用。
     * 委托给 [PictureInPictureManager] 处理 PIP 模式变化。
     * 
     * @param isInPictureInPictureMode 是否处于 PIP 模式
     * @param newConfig 新的配置信息
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(
            isInPictureInPictureMode, newConfig
        )

        pictureInPictureManager?.onPictureInPictureModeChanged(
            isInPictureInPictureMode,
            newConfig,
            isScreenLocked = ScreenDeviceUtil.isScreenLocked(this)
        )
        updateScreenshotListeningState()
    }

    /**
     * 注册应用锁监听器
     * 
     * 监听应用锁状态变化。当用户通过应用锁解锁时，更新状态管理器中的应用锁状态。
     */
    private fun registerAppUnlockListener() {
        appUnlockListener = {
            if (it) {
                // 用户刚刚通过应用锁解锁
                onGoingCallStateManager.setNeedAppLock(false)
            }
        }
        AppLockCallbackManager.addListener(callbackId, appUnlockListener)
    }

    /**
     * 注册通话 Activity 广播接收器
     * 
     * 注册 [CallActivityBroadcastReceiver] 以接收以下广播：
     * - 推流限制提示
     * - 通话超时通知
     * - 通话控制动作（接听、拒绝、挂断等）
     */
    private fun registerCallActivityReceiver() {
        callActivityBroadcastReceiver = CallActivityBroadcastReceiver(
            onPushStreamLimit = {
                showStyledPopTip(getString(R.string.call_push_stream_limit_tip), onDismiss = {})
            },
            onOngoingTimeout = { roomId ->
                if (roomId == onGoingCallStateManager.getCurrentRoomId()) {
                    showStyledPopTip(getString(R.string.call_callee_action_noanswer), onDismiss = { endCallAndClearResources() })
                }
            },
            onCallControl = { actionType, roomId ->
                handleCallAction(actionType, roomId)
            }
        )
        callActivityBroadcastReceiver?.register(this)
    }

    /**
     * 注册屏幕解锁广播接收器
     * 
     * 注册 [ScreenUnlockBroadcastReceiver] 以监听屏幕解锁事件。
     * 当屏幕解锁时，将通话界面带回前台。
     */
    private fun registerScreenUnlockReceiver() {
        screenUnlockBroadcastReceiver = ScreenUnlockBroadcastReceiver (
            onBringCallActivityToFront = { LCallManager.bringCallScreenToFront(this) },
            onGoingCallStateManager = onGoingCallStateManager
        )
        screenUnlockBroadcastReceiver?.register(this)
    }

    /**
     * 处理通话控制动作
     * 
     * 委托给 [CallActionHandler] 处理通话控制动作（接听、拒绝、挂断等）。
     * 
     * @param actionType 动作类型（接听、拒绝、挂断等）
     * @param roomId 房间 ID
     */
    private fun handleCallAction(actionType: String, roomId: String) {
        callActionHandler?.handleCallAction(actionType, roomId)
    }

    /**
     * 处理退出通话点击事件
     * 
     * 委托给 [CallExitHandler] 处理退出通话的逻辑。
     * 如果退出处理器未初始化，则直接结束通话。
     * 
     * @param params 退出参数，包含房间 ID、呼叫者 ID、通话角色等信息
     * @param callEndType 通话结束类型（离开或结束），默认为离开
     */
    private fun handleExitClick(
        params: CallExitParams,
        callEndType: CallEndType? = CallEndType.LEAVE,
    ) {
        callExitHandler?.handleExit(params, callEndType ?: CallEndType.LEAVE)
            ?: run {
                L.w { "[Call] LCallActivity: CallExitHandler is not initialized, ending call directly" }
                endCallAndClearResources()
            }
    }

    /**
     * 处理邀请用户点击事件
     * 
     * 委托给 [InviteCallHandler] 处理邀请用户加入通话的业务逻辑。
     * 如果成功邀请，可能会进入 PIP 模式。
     */
    private fun handleInviteUsersClick() {
        L.d { "[Call] LCallActivity handleInviteUsersClick" }
        inviteCallManager?.inviteUsers(context = this)
    }

    /**
     * 处理窗口缩小点击事件
     */
    private fun handleWindowZoomOutClick() {
        L.d { "[Call] LCallActivity handleWindowZoomOutClick" }
        // 尝试进入 PIP 模式。首先检查是否有"在其他应用上层显示"权限，
        if (!Settings.canDrawOverlays(this)) {
            // 如果权限不足，显示权限提示对话框。
            showPipPermissionToastOrEnterPipMode("windowZoomOut")
            return
        }
        // 然后检查系统是否支持 PIP 模式。
        if (pictureInPictureManager?.isSystemPipEnabledAndAvailable() == true) {
            enterPipModeIfPossible(tag = "windowZoomOut")
        } else {
            // 如果系统不支持 PIP，显示不支持提示对话框。
            AlertDialog.Builder(this)
                .setMessage(R.string.call_pip_not_supported_message)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                }.show()
        }
    }

    /**
     * 处理新的 Intent
     * 
     * 当 Activity 已经存在且收到新的 Intent 时调用（例如从通知栏启动）。
     * 解析新的 Intent 并处理通话参数。
     * 
     * @param intent 新的 Intent
     */
    override fun onNewIntent(intent: Intent) {
        val callIntent = getCallIntent()
        super.onNewIntent(intent)
        logIntent(callIntent)
        processIntent(callIntent)
    }

    /**
     * Activity 暂停时的回调
     */
    override fun onPause() {
        super.onPause()
        L.i { "[Call] LCallActivity onPause" }
        // 更新前台状态为 false
        onGoingCallStateManager.setIsInForeground(false)
        // 如果通话未结束，更新通知为通话样式（用于后台显示）
        if (!onGoingCallStateManager.isInCallEnding()) {
            callServiceManager?.updateOngoingCallNotification(true)
        }
        // 注销传感器监听器，防止内存泄漏
        proximitySensorManager?.unregister()
        screenshotDetector?.stopListening()
    }

    /**
     * Activity 恢复时的回调
     */
    override fun onResume() {
        super.onResume()
        L.i { "[Call] LCallActivity onResume" }
        // 如果通话正在进行，更新前台服务类型（确保权限授予后服务类型正确）
        if (onGoingCallStateManager.isInCalling()) {
            callServiceManager?.updateForegroundServiceType()
        }

        // 注册传感器监听器
        proximitySensorManager?.register()

        // 更新前台状态为 true
        onGoingCallStateManager.setIsInForeground(true)
        // 更新通知为普通样式（前台时不需要通话样式通知）
        callServiceManager?.updateOngoingCallNotification(false)

        // 如果之前正在请求权限，重置权限请求状态
        if (viewModel.isRequestingPermission()) {
            viewModel.callUiController.setRequestPermissionStatus(false)
        }
        updateScreenshotListeningState()
    }

    private fun updateScreenshotListeningState() {
        val conversationId = callIntent.conversationId
        if (conversationId.isNullOrEmpty()) {
            screenshotDetector?.stopListening()
            return
        }

        val callType = resolveCurrentCallType(conversationId)
        val isInstantCall = callType?.isInstant() == true
        val isInPipMode = onGoingCallStateManager.isInPipMode() || isInPictureInPictureMode
        val isInForeground = onGoingCallStateManager.isInForeground.value

        if (isInstantCall || isInPipMode || !isInForeground) {
            screenshotDetector?.stopListening()
            return
        }

        if (screenshotDetector == null) {
            screenshotDetector = ScreenshotDetector(
                activity = this,
                coroutineScope = lifecycleScope,
                onScreenshotDetected = {
                    L.i { "[Call][Screenshot] Screenshot detected, sending notification" }
                    handleScreenshotDetected()
                }
            )
        }
        screenshotDetector?.startListening()
    }

    private fun handleScreenshotDetected() {
        val conversationId = callIntent.conversationId ?: return
        val callType = resolveCurrentCallType(conversationId) ?: return
        if (callType.isInstant() || onGoingCallStateManager.isInPipMode() || isInPictureInPictureMode) {
            return
        }
        callToChatController.sendScreenshotNotification(conversationId, callType)
    }

    private fun resolveCurrentCallType(conversationId: String): CallType? {
        val type = onGoingCallStateManager.callType().ifEmpty { callIntent.callType }
        val resolved = CallType.fromString(type)
        if (resolved != null) {
            return resolved
        }
        return if (ValidatorUtil.isGid(conversationId)) CallType.GROUP else CallType.ONE_ON_ONE
    }

    /**
     * 初始化画中画管理器
     * 用于管理 PIP 模式的初始化和状态
     */
    private fun initializePictureInPictureManager() {
        pictureInPictureManager = PictureInPictureManager(
            activity = this,
            lifecycleScope = lifecycleScope,
            lifecycle = lifecycle,
            onPipModeChanged = { isInPipMode ->
                viewModel.callUiController.setPipModeEnabled(isInPipMode)
                onGoingCallStateManager.setIsInPipMode(isInPipMode)
            },
            onPipClosed = {
                onGoingCallStateManager.getCurrentRoomId()?.let { roomId ->
                    val callExitParams = CallExitParams(
                        roomId,
                        callIntent.callerId,
                        callRole,
                        onGoingCallStateManager.callType(),
                        onGoingCallStateManager.getConversationId()
                    )
                    handleExitClick(callExitParams)
                }
            }
        )
        pictureInPictureManager?.initialize()
    }

    /**
     * 尝试进入画中画（PIP）模式
     * 
     * 仅在 Android O（API 26）及以上版本支持。
     * 委托给 [PictureInPictureManager] 处理 PIP 模式进入逻辑。
     * 
     * @param tag 调用标签，用于日志记录，便于追踪调用来源
     * @return 是否成功进入 PIP 模式
     */
    private fun enterPipModeIfPossible(tag: String? = null): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pictureInPictureManager?.enterPipMode(tag) ?: false
        } else {
            false
        }
    }

    /**
     * 倒计时结束时的震动反馈
     * 
     * 在倒计时结束时触发震动，提供触觉反馈。
     * 需要震动权限，如果没有权限则直接返回。
     * 
     * 使用 CallVibrationManager 进行单次短震动。
     */
    fun countDownEndVibrate() {
        if (checkSelfPermission(android.Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        vibrationManager.vibrateOnce(200L, 200)
    }

    /**
     * 创建通话退出参数
     * 
     * 根据当前通话状态创建 [CallExitParams] 对象，用于退出通话时传递参数。
     * 
     * @return 包含房间 ID、呼叫者 ID、通话角色、通话类型和会话 ID 的退出参数对象
     */
    private fun createCallExitParams() = CallExitParams(
        viewModel.getRoomId(),
        callIntent.callerId,
        callRole,
        onGoingCallStateManager.callType(),
        onGoingCallStateManager.getConversationId()
    )

    /**
     * 处理底部通话结束动作
     * 
     * 根据用户选择的动作执行相应操作：
     * - [BottomCallEndAction.END_CALL]：结束通话
     * - [BottomCallEndAction.LEAVE_CALL]：离开通话
     * - 其他：取消操作，恢复底部工具栏显示
     * 
     * @param action 用户选择的通话结束动作
     */
    private fun handleBottomCallEndAction(action: BottomCallEndAction) {
        when (action) {
            BottomCallEndAction.END_CALL -> {
                L.i { "[call] LCallActivity onClick End" }
                if(viewModel.hasOtherActiveSpeaker()) {
                    viewModel.callUiController.setShowBottomCallEndViewEnable(false)
                    callDialogManager?.showEndCallForAllDialog({ actionType ->
                        when (actionType) {
                            DialogActionType.ON_CONFIRM -> {
                                handleExitClick(
                                    createCallExitParams(),
                                    CallEndType.END
                                )
                            }
                            DialogActionType.ON_CANCEL -> {
                                callDialogManager?.dismissEndCallForAllDialog()
                            }
                        }
                    })
                } else {
                    handleExitClick(
                        createCallExitParams(),
                        CallEndType.END
                    )
                    viewModel.callUiController.setShowBottomCallEndViewEnable(false)
                }
            }

            BottomCallEndAction.LEAVE_CALL -> {
                L.i { "[call] LCallActivity onClick Leave" }
                handleExitClick(
                    createCallExitParams(),
                    CallEndType.LEAVE
                )
                viewModel.callUiController.setShowBottomCallEndViewEnable(false)
            }

            else -> {
                viewModel.callUiController.setShowBottomCallEndViewEnable(false)
                viewModel.callUiController.setShowBottomToolBarViewEnabled(true)
            }
        }
    }

    private fun handleInviteViewAction(action: InviteViewState) {
        when (action) {
            InviteViewState.INVITE -> {
                viewModel.callUiController.setShowInviteViewEnable(false)
                lifecycleScope.launch(Dispatchers.IO) {
                    inviteCallManager?.inviteMembers(
                        callback = { state, invitees ->
                            L.i { "[Call] invite call state: $state invitees:$invitees" }
                            if (state == InviteRequestState.SUCCESS) {
                                if (onGoingCallStateManager.callType() != CallType.GROUP.type) {
                                    viewModel.callUiController.setCriticalAlertEnable(true)
                                }
                                viewModel.addAwaitingJoinInvitees(invitees)
                            }
                            val isOneOneCall = onGoingCallStateManager.callType() == CallType.ONE_ON_ONE.type
                            if (state == InviteRequestState.SUCCESS && isOneOneCall) {
                                viewModel.switchToInstantCall()
                                viewModel.stopRingToneAndTimeoutCheck()
                                viewModel.handleConnectedState()
                            }
                            inviteCallManager?.resetState()
                        }
                    )
                }
            }
            InviteViewState.DISMISS -> {
                viewModel.callUiController.setShowInviteViewEnable(false)
                inviteCallManager?.resetState()
            }
        }
    }

    /**
     * 初始化距离传感器管理器
     * 用于检测通话时手机是否靠近脸部，防止误触
     */
    private fun initializeProximitySensor() {
        proximitySensorManager = ProximitySensorManager(
            activity = this,
            isScreenSharingProvider = { viewModel.callUiController.isShareScreening.value }
        )
        proximitySensorManager?.initialize()
    }

    /**
     * 初始化对话框管理器
     * 统一管理所有对话框，简化状态管理
     */
    private fun initializeDialogManager() {
        callDialogManager = CallDialogManager(
            activity = this,
            lifecycleScope = lifecycleScope,
            viewModel = viewModel,
            callIntent = callIntent,
            callRole = callRole,
            onGoingCallStateManager = onGoingCallStateManager,
            userManager = userManager,
            onExitCall = { params -> handleExitClick(params) },
            onEndCall = { endCallAndClearResources() }
        )
    }

    /**
     * 初始化邀请用户管理器
     * 统一管理邀请用户加入通话的业务逻辑
     */
    private fun initializeInviteCallManager() {
        inviteCallManager = InviteCallHandler(
            viewModel = viewModel,
            callToChatController = callToChatController,
            contactorCacheManager = contactorCacheManager,
            callIntent = callIntent,
            scope = lifecycleScope
        )
    }

    /**
     * 初始化生命周期观察者
     * 统一管理 ViewModel 状态的生命周期监听
     */
    private fun initializeLifecycleObserver() {
        callLifecycleObserver = CallLifecycleObserver(
            viewModel = viewModel,
            onGoingCallStateManager = onGoingCallStateManager,
            callToChatController = callToChatController,
            callErrorHandler = callErrorHandler,
            callDialogManager = callDialogManager,
            callConfig = callConfig
        )
        lifecycle.addObserver(callLifecycleObserver!!)
    }

    /**
     * 初始化清理管理器
     * 统一管理 Activity 销毁时的资源清理
     */
    private fun initializeCleanupManager() {
        callCleanupManager = CallCleanupManager(
            lifecycle = lifecycle,
            context = this,
            callbackId = callbackId
        )
    }

    /**
     * 初始化服务管理器
     * 统一管理通话前台服务的启动、停止和更新
     */
    private fun initializeCallServiceManager() {
        callServiceManager = CallServiceManager(
            context = this,
            callToChatController = callToChatController
        )
        callServiceManager?.startOngoingCallService()
    }

    /**
     * 更新前台服务类型
     * 当权限授予后更新服务类型，确保服务可以在后台使用麦克风/摄像头
     * 此方法供外部（如 Compose UI）调用
     */
    fun updateForegroundServiceType() {
        callServiceManager?.updateForegroundServiceType()
    }


    /**
     * 注册返回键处理
     * 
     * 拦截返回键事件，尝试进入 PIP 模式或显示权限提示对话框。
     * 防止用户意外退出通话界面。
     */
    private fun registerOnBackPressedHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                L.i { "[Call] LCallActivity intercept on back press" }
                showPipPermissionToastOrEnterPipMode("back pressed")
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    /**
     * 显示 PIP 权限提示或进入 PIP 模式
     * 
     * 委托给 [CallDialogManager] 显示 PIP 权限提示对话框。
     * 如果用户授予权限，则尝试进入 PIP 模式。
     * 
     * @param tag 调用标签，用于日志记录
     */
    private fun showPipPermissionToastOrEnterPipMode(tag: String?) {
        callDialogManager?.showPipPermissionDialog(tag) { enterPipModeIfPossible(it) }
    }

    /**
     * 允许在锁屏界面显示
     * 
     * 设置窗口标志，使 Activity 可以在锁屏界面显示，并在解锁时自动点亮屏幕。
     * Android O MR1 及以上版本使用新的 API。
     */
    private fun allowOnLockScreen() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    /**
     * 检查用户是否设置了应用锁（图案或密码）。
     * 
     * @return 如果应用锁已启用返回 true，否则返回 false
     */
    private fun isAppLockEnabled(): Boolean {
        if (!::userManager.isInitialized) return false
        val user = userManager.getUserData() ?: return false
        val hasPattern = !user.pattern.isNullOrEmpty()
        val hasPasscode = !user.passcode.isNullOrEmpty()
        return hasPattern || hasPasscode
    }

    /**
     * 显示 Toast 提示消息，并在显示后执行回调。
     * 
     * @param message 要显示的消息文本
     * @param onDismiss 消息显示后的回调，默认为空操作
     */
    fun showStyledPopTip(message: String, onDismiss: () -> Unit = {}) {
        ToastUtil.show(message)
        onDismiss()
    }
}
