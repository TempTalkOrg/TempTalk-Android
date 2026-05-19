package com.difft.android.call

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.difft.android.base.call.CallRole
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.CountdownTimer
import com.difft.android.base.user.PromptReminder
import com.difft.android.base.user.UserManager
import com.difft.android.base.user.defaultBarrageTexts
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.handler.CallActionHandler
import com.difft.android.call.handler.CallErrorHandler
import com.difft.android.call.handler.CallExitHandler
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.manager.CallCleanupManager
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallDialogManager
import com.difft.android.call.manager.CallLifecycleObserver
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.manager.CallScreenshotController
import com.difft.android.call.manager.CallServiceManager
import com.difft.android.call.manager.CallVibrationManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.PictureInPictureManager
import com.difft.android.call.manager.ProximitySensorManager
import com.difft.android.call.receiver.CallActivityBroadcastReceiver
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.util.ScreenDeviceUtil
import com.difft.android.network.config.GlobalConfigsManager
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


/**
 * Call activity.
 *
 * Owns the call screen lifecycle, UI interactions and resource management.
 * Business logic is delegated to dedicated Manager classes to keep this
 * class small and maintainable.
 *
 * Responsibilities:
 * - Drive the Activity lifecycle.
 * - Hold references to the various Managers (error/exit/service/…).
 * - Orchestrate interactions between those Managers.
 *
 * Initialization and event-handling logic lives in:
 * - `LCallActivityInitializer.kt` — initializeXxx / registerXxx extensions.
 * - `LCallActivityEventHandler.kt` — handleXxx UI event extensions.
 * - [CallScreenshotController] — screenshot detection and notification.
 */
@AndroidEntryPoint
class LCallActivity : AppCompatActivity() {

    @Inject
    internal lateinit var callToChatController: LCallToChatController

    @Inject
    internal lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    internal lateinit var userManager: UserManager

    @Inject
    internal lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    internal lateinit var callDataManagerLazy: Lazy<CallDataManager>

    @Inject
    internal lateinit var vibrationManager: CallVibrationManager

    @Inject
    internal lateinit var ringtoneManager: CallRingtoneManager

    @Inject
    internal lateinit var contactorCacheManager: ContactorCacheManager

    internal val callDataManager: CallDataManager by lazy { callDataManagerLazy.get() }

    internal lateinit var callIntent: CallIntent

    internal val callRole: CallRole by lazy {
        if (callIntent.callRole == CallRole.CALLER.type) CallRole.CALLER else CallRole.CALLEE
    }

    internal val callConfig: CallConfig by lazy {
        globalConfigsManager.getNewGlobalConfigs()?.data?.call ?: CallConfig(
            autoLeave = AutoLeave(promptReminder = PromptReminder()),
            chatPresets = defaultBarrageTexts,
            chat = CallChat(),
            countdownTimer = CountdownTimer()
        )
    }

    internal val autoHideTimeout: Long by lazy {
        callConfig.chat?.autoHideTimeout ?: CallChat().autoHideTimeout
    }

    internal val muteOtherEnabled: Boolean by lazy { callConfig.muteOtherEnabled }

    internal var pictureInPictureManager: PictureInPictureManager? = null
    internal var proximitySensorManager: ProximitySensorManager? = null
    internal var callErrorHandler: CallErrorHandler? = null
    internal var callExitHandler: CallExitHandler? = null
    internal var callActionHandler: CallActionHandler? = null
    internal var callLifecycleObserver: CallLifecycleObserver? = null

    /**
     * 屏幕点击节流时间戳，由 [LCallActivityEventHandler.handleScreenClick]
     * 使用。绑定到 Activity 实例而不是文件级，避免 Activity 被重建后上一个
     * session 残留的 uptimeMillis 把新实例的第一次点击静默丢弃。
     */
    internal var lastScreenClickMs: Long = 0L
    internal var callDialogManager: CallDialogManager? = null
    internal var inviteCallManager: InviteCallHandler? = null
    internal var callServiceManager: CallServiceManager? = null
    internal var callCleanupManager: CallCleanupManager? = null
    internal var callActivityBroadcastReceiver: CallActivityBroadcastReceiver? = null
    internal var screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver? = null
    internal var screenshotController: CallScreenshotController? = null

    internal lateinit var backPressedCallback: OnBackPressedCallback
    internal lateinit var appUnlockListener: (Boolean) -> Unit

    internal val callbackId = "LCallActivity_${System.identityHashCode(this)}"

    internal val viewModel: LCallViewModel by viewModelByFactory {
        LCallViewModel(
            e2eeEnable = true,
            application = application,
            callIntent = callIntent,
            callConfig = callConfig,
            callRole = callRole,
            initialVoicePreset = VoicePreset.fromSdkKey(
                userManager.getUserData()?.callVoiceChangerPreset
                    ?: VoicePreset.ORIGINAL.sdkKey
            ),
        )
    }

    internal val handler = Handler(Looper.getMainLooper())

    /**
     * Timestamp when the window last lost focus.
     *
     * Kept on the Activity to avoid an initialization race window: e.g.
     * `CallWaitDialogUtil` may fire `onWindowFocusChanged` before
     * [screenshotController] is created. [CallScreenshotController] reads
     * this value through a provider.
     */
    @Volatile
    internal var windowFocusLostAt: Long = 0L

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocusLostAt = if (hasFocus) 0L else System.currentTimeMillis()
    }

    /**
     * Activity creation and bootstrapping.
     *
     * Sequence (extension implementations live in `LCallActivityInitializer.kt`):
     * 1. Handle illegal recreation after process death.
     * 2. Parse Intent; bring up error/exit/action handlers.
     * 3. Initialize call state and UI.
     * 4. Initialize managers, lifecycle observer and cleanup manager.
     * 5. Register listeners and configure window attributes.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        L.i { "[Call] LCallActivity: onCreate" }
        super.onCreate(savedInstanceState)

        // After process death (e.g. native crash), the system may restore this
        // Activity from the separate call task. All in-memory call state
        // (OnGoingCallStateManager, LiveKit Room) is gone, so continuing would
        // start a ghost ForegroundService with no real call behind it.
        if (savedInstanceState != null && !onGoingCallStateManager.isInCalling()) {
            L.w { "[Call] LCallActivity: Restored after process death with no active call, finishing" }
            finish()
            return
        }

        // Placeholder content to ensure ViewTreeLifecycleOwner is installed,
        // avoiding "ViewTreeLifecycleOwner not found" crashes when CallWaitDialogUtil
        // (via ComposeDialogManager) attaches a ComposeView inside initializeView.
        setContentView(android.widget.FrameLayout(this))

        handleIntent(savedInstanceState)
        initializeErrorHandler()
        initializeExitHandler()
        initializeCallActionHandler()
        initializeState()
        initializeView()
        initializeManagers()
        initializeLifecycleObserver()
        initializeCleanupManager()
        registerListeners()
        configureWindow()
    }

    /**
     * Activity teardown.
     *
     * Delegates to [CallCleanupManager] for the unified resource-release
     * pass and then nulls out local references to prevent leaks.
     */
    override fun onDestroy() {
        super.onDestroy()
        L.i { "[Call] LCallActivity onDestroy start." }

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
            viewModel = viewModel,
            backPressedCallback = if (::backPressedCallback.isInitialized) backPressedCallback else null
        )

        callLifecycleObserver = null
        callDialogManager = null
        proximitySensorManager = null
        pictureInPictureManager = null
        callActivityBroadcastReceiver = null
        screenUnlockBroadcastReceiver = null
        callServiceManager = null
        callCleanupManager = null
        screenshotController?.release()
        screenshotController = null

        L.i { "[Call] LCallActivity onDestroy end." }
    }

    /**
     * Invoked when the user leaves the Activity (Home key / task switch).
     *
     * If no permission dialog is currently visible, attempt to enter PIP
     * mode or surface the PIP permission prompt.
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

    override fun onNewIntent(intent: Intent) {
        // Note: this intentionally reads `this.intent` (the old Intent still
        // held by the Activity) BEFORE calling super.onNewIntent(), to preserve
        // the historical semantics. Android does not auto-setIntent(); if the
        // new Intent ever needs to be honored, call setIntent(intent)
        // explicitly and read the new value here.
        // Renamed to `oldCallIntent` to avoid shadowing the class-level
        // `callIntent` field.
        val oldCallIntent = CallIntent(this.intent)
        super.onNewIntent(intent)
        L.d { "[Call] LCallActivity logIntent:$oldCallIntent" }
        processIntent(oldCallIntent)
    }

    override fun onPause() {
        super.onPause()
        L.i { "[Call] LCallActivity onPause" }
        onGoingCallStateManager.setIsInForeground(false)
        if (!onGoingCallStateManager.isInCallEnding()) {
            callServiceManager?.updateOngoingCallNotification(true)
        }
        proximitySensorManager?.unregister()
        screenshotController?.stopListening()
    }

    override fun onResume() {
        super.onResume()
        L.i { "[Call] LCallActivity onResume" }
        if (onGoingCallStateManager.isInCalling()) {
            callServiceManager?.updateForegroundServiceType()
        }
        proximitySensorManager?.register()

        onGoingCallStateManager.setIsInForeground(true)
        callServiceManager?.updateOngoingCallNotification(false)

        if (viewModel.isRequestingPermission()) {
            viewModel.callUiController.setRequestPermissionStatus(false)
        }
        updateScreenshotListeningState()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureManager?.onPictureInPictureModeChanged(
            isInPictureInPictureMode,
            newConfig,
            isScreenLocked = ScreenDeviceUtil.isScreenLocked(this)
        )
        updateScreenshotListeningState()
    }

    /**
     * Override getResources to force fontScale back to 1.0f so that the
     * system font-scale setting does not break the call layout.
     * updateConfiguration is deprecated but has no equivalent per-access
     * replacement API.
     */
    @Suppress("DEPRECATION")
    override fun getResources(): Resources {
        val res = super.getResources()
        if (res.configuration.fontScale != DEFAULT_FONT_SCALE) {
            val newConfig = Configuration(res.configuration)
            newConfig.fontScale = DEFAULT_FONT_SCALE
            res.updateConfiguration(newConfig, res.displayMetrics)
        }
        return res
    }

    /**
     * End the call and release resources.
     *
     * Marks the exit state, triggers the ViewModel cleanup (heavy I/O runs
     * inside viewModelScope) and closes the Activity immediately.
     */
    internal fun endCallAndClearResources() {
        onGoingCallStateManager.setIsInCallEnding(true)
        runOnUiThread {
            viewModel.doExitClear()
            finishAndRemoveTask()
        }
    }

    /**
     * Vibration feedback when the countdown finishes.
     *
     * Requires the VIBRATE permission; returns silently if not granted.
     */
    fun countDownEndVibrate() {
        if (checkSelfPermission(android.Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        vibrationManager.vibrateOnce(200L, 200)
    }

    /**
     * Update foreground-service types; called by the Compose UI once
     * the corresponding runtime permission has been granted.
     */
    fun updateForegroundServiceType() {
        callServiceManager?.updateForegroundServiceType()
    }

    /**
     * Show a toast message and run the supplied callback afterwards.
     */
    fun showStyledPopTip(message: String, onDismiss: () -> Unit = {}) {
        ToastUtil.show(message)
        onDismiss()
    }

    /**
     * Refresh the screenshot listening state.
     *
     * Delegates to [CallScreenshotController], which decides whether the
     * detector should be active based on the current call type, PIP mode
     * and foreground/background state.
     */
    internal fun updateScreenshotListeningState() {
        screenshotController?.updateListeningState()
    }

    /**
     * Allow this Activity to be shown over the lock screen.
     *
     * API 27+: handled by `android:showWhenLocked` / `android:turnScreenOn`
     * manifest attributes, which are processed by the system before onCreate()
     * — no Binder IPC needed at runtime.
     *
     * API 26: falls back to deprecated window flags (local, no IPC).
     */
    internal fun allowOnLockScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    /**
     * Check whether the user has configured an app lock (pattern or password).
     */
    internal fun isAppLockEnabled(): Boolean {
        if (!::userManager.isInitialized) return false
        val user = userManager.getUserData() ?: return false
        val hasPattern = !user.pattern.isNullOrEmpty()
        val hasPasscode = !user.passcode.isNullOrEmpty()
        return hasPattern || hasPasscode
    }

    companion object {
        const val ACTION_IN_CALLING_CONTROL = "ACTION_IN_CALLING_CONTROL"
        const val EXTRA_CONTROL_TYPE = "EXTRA_CONTROL_TYPE"
        const val EXTRA_PARAM_ROOM_ID = "EXTRA_PARAM_ROOM_ID"
        private const val DEFAULT_FONT_SCALE = 1.0f
    }
}
