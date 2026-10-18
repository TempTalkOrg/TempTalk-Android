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
import android.view.Display
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.call.CallRole
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.OrientationDisplayWatcher
import com.difft.android.base.utils.OrientationPolicy
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.permission.CallMediaPermission
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.manager.CallCleanupManager
import com.difft.android.call.manager.CallDialogManager
import com.difft.android.call.manager.CallLifecycleObserver
import com.difft.android.call.manager.CallScreenshotController
import com.difft.android.call.manager.CallVibrationManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.PictureInPictureManager
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver
import com.difft.android.call.service.OngoingCallServiceController
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionHolder
import com.difft.android.call.session.resolveCallSession
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.base.utils.hideNavigationBar
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
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
    internal lateinit var sessionHolder: CallSessionHolder

    @Inject
    internal lateinit var userManager: UserManager

    @Inject
    internal lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    internal lateinit var vibrationManager: CallVibrationManager

    @Inject
    internal lateinit var contactorCacheManager: ContactorCacheManager

    @Inject
    internal lateinit var ongoingCallService: OngoingCallServiceController

    /** The live session this page attaches to, resolved in [onCreate]. */
    internal lateinit var session: CallSession

    /** The call this page shows: the session's start/join intent, whatever intent opened the page. */
    internal lateinit var callIntent: CallIntent

    /** The intent that opened this page; only its wait-dialog flag is read. */
    internal lateinit var launchIntent: CallIntent

    internal val callRole: CallRole get() = session.params.callRole

    internal val callConfig: CallConfig get() = session.params.callConfig

    internal val autoHideTimeout: Long by lazy {
        callConfig.chat?.autoHideTimeout ?: CallChat().autoHideTimeout
    }

    internal val muteOtherEnabled: Boolean by lazy { callConfig.muteOtherEnabled }

    internal var pictureInPictureManager: PictureInPictureManager? = null
    internal var callLifecycleObserver: CallLifecycleObserver? = null

    /**
     * 屏幕点击节流时间戳，由 [LCallActivityEventHandler.handleScreenClick]
     * 使用。绑定到 Activity 实例而不是文件级，避免 Activity 被重建后上一个
     * session 残留的 uptimeMillis 把新实例的第一次点击静默丢弃。
     */
    internal var lastScreenClickMs: Long = 0L
    internal var callDialogManager: CallDialogManager? = null
    internal var inviteCallManager: InviteCallHandler? = null
    internal var callCleanupManager: CallCleanupManager? = null
    internal var screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver? = null
    internal var screenshotController: CallScreenshotController? = null

    internal lateinit var backPressedCallback: OnBackPressedCallback

    // Registered at construction time (required before RESUMED). Results route through
    // LCallActivityMediaPermissions.onMediaPermissionResult; taps come from the Compose toolbar
    // via onMediaControlTapped.
    private val micPermissionLauncher = registerPermission {
        onMediaPermissionResult(CallMediaPermission.Microphone, it)
    }
    private val cameraPermissionLauncher = registerPermission {
        onMediaPermissionResult(CallMediaPermission.Camera, it)
    }

    internal fun mediaPermissionLauncher(permission: CallMediaPermission): PermissionUtil.Permission =
        when (permission) {
            CallMediaPermission.Microphone -> micPermissionLauncher
            CallMediaPermission.Camera -> cameraPermissionLauncher
        }

    internal val viewModel: LCallViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<LCallViewModelFactory> { it.create(session) }
    })

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
     * 1. Resolve the live call session, or finish when this page has none.
     * 2. Attach to the session and bring up the session finish effect.
     * 3. Initialize call state and UI.
     * 4. Initialize managers, lifecycle observer and cleanup manager.
     * 5. Register listeners and configure window attributes.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        L.i { "[Call] LCallActivity: onCreate" }
        // Pre-super, like BaseActivity: the manifest `locked` keeps the rotation the call was
        // launched over (a landscape app, say) only until this writes the real target, so the
        // write must land before the first frame. Safe here — this activity is opaque.
        policyAppliedOrientation = OrientationPolicy.applyTo(this)
        // Created inside a pending unfold (an incoming call as the device opens): applyTo held
        // this screen open, so it needs the same delayed re-lock BaseActivity's screens get in
        // case no display switch follows.
        val heldForUnfold = OrientationPolicy.isUnfoldPending()
        super.onCreate(savedInstanceState)
        if (heldForUnfold) OrientationPolicy.scheduleUnfoldRelock(this) { reapplyOrientationPolicyIfOwned() }

        // The call's entry point started the session before launching this page. No live session (a
        // restore after process death, a call that already ended) or a launch for another session
        // means there is nothing to show: finish without touching call state.
        val resolved = resolveCallSession(sessionHolder.current.value, CallIntent(intent).sessionKey)
        if (resolved == null) {
            L.w { "[Call] LCallActivity: no live session for this page, finishing restored=${savedInstanceState != null}" }
            finish()
            return
        }
        session = resolved

        // Placeholder content to ensure ViewTreeLifecycleOwner is installed,
        // avoiding "ViewTreeLifecycleOwner not found" crashes when CallWaitDialogUtil
        // (via ComposeDialogManager) attaches a ComposeView inside initializeView.
        setContentView(android.widget.FrameLayout(this))

        handleIntent(savedInstanceState)
        initializeSessionEffects()
        initializeState()
        initializeView()
        initializeManagers()
        initializeLifecycleObserver()
        initializeCleanupManager()
        registerListeners()
        configureWindow()
    }

    // The orientation value the policy last wrote for this activity (see BaseActivity's
    // twin field): re-applies happen only while requestedOrientation still equals it, so
    // ScreenSharingView's deliberate landscape lock is never clobbered mid-share.
    internal var policyAppliedOrientation: Int? = null

    // Same display-level replay BaseActivity has: a fold the OS answers with a letterbox delivers
    // no configuration change at all, and a call is the screen most likely to be open across a fold.
    private val orientationDisplayWatcher = OrientationDisplayWatcher(this) { display ->
        reapplyOrientationPolicyIfOwned(display)
    }

    /**
     * Re-apply the display-level orientation policy when the configuration changes: this
     * activity's manifest configChanges swallow every fold key, so a mid-call unfold never
     * recreates it and the folded posture's portrait lock would otherwise stick on the inner
     * display. Not a BaseActivity, so it cannot inherit that re-apply. Skipped while pinned
     * (PiP) and while another owner (screen-share landscape lock) holds the orientation.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInPictureInPictureMode) return
        reapplyOrientationPolicyIfOwned()
    }

    private fun reapplyOrientationPolicyIfOwned(display: Display? = null) {
        val owned = policyAppliedOrientation?.let { requestedOrientation == it } == true
        if (owned) {
            policyAppliedOrientation = OrientationPolicy.applyTo(this, display)
        }
    }

    override fun onStart() {
        super.onStart()
        orientationDisplayWatcher.start()
    }

    override fun onStop() {
        orientationDisplayWatcher.stop()
        super.onStop()
    }

    /**
     * Activity teardown. Delegates to [CallCleanupManager], which releases only this page
     * instance's resources (the call itself belongs to the session), then nulls out local
     * references to prevent leaks.
     */
    override fun onDestroy() {
        super.onDestroy()
        L.i { "[Call] LCallActivity onDestroy start. isChangingConfigurations:$isChangingConfigurations" }

        callCleanupManager?.cleanup(
            lifecycleObserver = callLifecycleObserver,
            dialogManager = callDialogManager,
            pictureInPictureManager = pictureInPictureManager,
            screenUnlockBroadcastReceiver = screenUnlockBroadcastReceiver,
            backPressedCallback = if (::backPressedCallback.isInitialized) backPressedCallback else null,
        )

        callLifecycleObserver = null
        callDialogManager = null
        pictureInPictureManager = null
        screenUnlockBroadcastReceiver = null
        callCleanupManager = null
        screenshotController?.release()
        screenshotController = null

        L.i { "[Call] LCallActivity onDestroy end." }
    }

    /**
     * Invoked when the user leaves the Activity (Home key / task switch).
     *
     * Delegates to [enterPipOnUserLeaveHint], which decides whether this hint
     * reflects an actual intent to leave before entering PIP mode or surfacing
     * the PIP permission prompt.
     */
    @SuppressLint("MissingSuperCall")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        L.i { "[Call] LCallActivity onUserLeaveHint" }
        enterPipOnUserLeaveHint()
    }

    /**
     * Re-entry (singleInstance) changes no call state and the new Intent is not adopted (no
     * setIntent). A START/JOIN launched for another session reached this page while it was going
     * away: this page closes and the launch is replayed so a new page attaches to that session.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val key = CallIntent(intent).sessionKey
        if (!::session.isInitialized || key == null || key == session.params.launchKey) {
            L.i { "[Call] LCallActivity onNewIntent" }
            return
        }
        L.w { "[Call] LCallActivity onNewIntent for another session, relaunching id=${session.id}" }
        finishAndRemoveTask()
        val app = applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                L.e(e) { "[Call] LCallActivity relaunch failed" }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        L.i { "[Call] LCallActivity onPause" }
        onGoingCallStateManager.setIsInForeground(false)
        screenshotController?.stopListening()
    }

    override fun onResume() {
        super.onResume()
        L.i { "[Call] LCallActivity onResume" }
        // A visible page: issue a start the session deferred, then refresh the service type. Both
        // are keyed to this page's session and do nothing unless it owns the service.
        val sessionId = viewModel.sessionId
        ongoingCallService.ensureStarted(sessionId)
        ongoingCallService.updateServiceType(sessionId)

        onGoingCallStateManager.setIsInForeground(true)

        if (viewModel.isRequestingPermission()) {
            viewModel.callUiController.setRequestPermissionStatus(false)
        }
        // Settings return / any foreground return: refresh badge + tap routing state.
        // Never auto-enables anything — a fresh grant still waits for the next tap.
        viewModel.mediaPermissions.refresh(this)
        updateScreenshotListeningState()
        window.hideNavigationBar()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureManager?.onPictureInPictureModeChanged(
            isInPictureInPictureMode,
            newConfig
        )
        updateScreenshotListeningState()
        // Backstop for the PiP skips in onConfigurationChanged and the display watcher: a fold
        // while pinned is ignored on purpose, so re-apply once the window is un-pinned (mirrors
        // BaseActivity).
        if (!isInPictureInPictureMode) {
            reapplyOrientationPolicyIfOwned()
        }
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
     * Marks the exit state, ends the call session (its cleanup, heavy I/O included, runs on
     * `appScope` + IO) and closes the Activity immediately.
     */
    internal fun endCallAndClearResources() {
        viewModel.markEnding()
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
        ongoingCallService.updateServiceType(viewModel.sessionId)
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
        private const val DEFAULT_FONT_SCALE = 1.0f
    }
}
