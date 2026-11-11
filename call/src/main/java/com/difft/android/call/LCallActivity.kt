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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.call.CallRole
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.FoldDiagnostics
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
import com.difft.android.call.ui.screenshare.applyScreenShareOrientation
import com.difft.android.call.ui.tabletop.CallFoldTracker
import com.difft.android.base.utils.hideNavigationBar
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import javax.inject.Inject



@AndroidEntryPoint
class LCallActivity : AppCompatActivity(), FoldDiagnostics.Host {

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

    
    internal lateinit var session: CallSession

    
    internal lateinit var callIntent: CallIntent

    
    internal lateinit var launchIntent: CallIntent

    internal val callRole: CallRole get() = session.params.callRole

    internal val callConfig: CallConfig get() = session.params.callConfig

    internal val autoHideTimeout: Long by lazy {
        callConfig.chat?.autoHideTimeout ?: CallChat().autoHideTimeout
    }

    internal val muteOtherEnabled: Boolean by lazy { callConfig.muteOtherEnabled }

    internal var pictureInPictureManager: PictureInPictureManager? = null
    internal var callLifecycleObserver: CallLifecycleObserver? = null

    
    internal var lastScreenClickMs: Long = 0L
    internal var callDialogManager: CallDialogManager? = null
    internal var inviteCallManager: InviteCallHandler? = null
    internal var callCleanupManager: CallCleanupManager? = null
    internal var screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver? = null
    internal var screenshotController: CallScreenshotController? = null

    internal lateinit var backPressedCallback: OnBackPressedCallback

    
    internal val foldTracker = CallFoldTracker(this)

    
    
    
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

    
    override fun onCreate(savedInstanceState: Bundle?) {
        L.i { "[Call] LCallActivity: onCreate" }
        
        
        
        policyAppliedOrientation = OrientationPolicy.applyTo(this)
        
        
        
        val heldForUnfold = OrientationPolicy.isUnfoldPending()
        super.onCreate(savedInstanceState)
        if (heldForUnfold) OrientationPolicy.scheduleUnfoldRelock(this) { reapplyOrientationPolicyIfOwned() }

        
        
        
        val resolved = resolveCallSession(sessionHolder.current.value, CallIntent(intent).sessionKey)
        if (resolved == null) {
            L.w { "[Call] LCallActivity: no live session for this page, finishing restored=${savedInstanceState != null}" }
            finish()
            return
        }
        session = resolved
        foldTracker.attach()

        
        
        
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

    
    
    
    
    internal var policyAppliedOrientation: Int? = null

    
    
    
    internal var screenShareOrientationActive by mutableStateOf(false)

    
    
    internal var foldHalfOpen = false
        private set

    
    internal fun onFoldHalfOpenChanged(halfOpen: Boolean) {
        if (foldHalfOpen == halfOpen) return
        foldHalfOpen = halfOpen
        if (screenShareOrientationActive && !isInPictureInPictureMode) {
            policyAppliedOrientation = applyScreenShareOrientation(this, halfOpen = halfOpen)
        }
    }

    
    
    private val orientationDisplayWatcher = OrientationDisplayWatcher(this) { display ->
        reapplyOrientationPolicyIfOwned(display)
    }

    






    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        FoldDiagnostics.onConfigurationArrived(this, via = "onConfigurationChanged")
        if (isInPictureInPictureMode) return
        reapplyOrientationPolicyIfOwned()
    }

    private fun reapplyOrientationPolicyIfOwned(display: Display? = null) {
        if (screenShareOrientationActive) {
            policyAppliedOrientation = applyScreenShareOrientation(this, display, foldHalfOpen)
            return
        }
        val owned = policyAppliedOrientation?.let { requestedOrientation == it } == true
        if (owned) {
            policyAppliedOrientation = OrientationPolicy.applyTo(this, display)
        }
    }

    override fun onStart() {
        super.onStart()
        orientationDisplayWatcher.start()
    }

    
    override fun foldDiagExtras(): String {
        val owned = policyAppliedOrientation?.let { requestedOrientation == it } == true
        val tabletop = if (::session.isInitialized) viewModel.callUiController.dockPinned.value.toString() else "none"
        return "owned=$owned share=$screenShareOrientationActive tabletop=$tabletop"
    }

    override fun onStop() {
        orientationDisplayWatcher.stop()
        super.onStop()
    }

    




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

    






    @SuppressLint("MissingSuperCall")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        L.i { "[Call] LCallActivity onUserLeaveHint" }
        enterPipOnUserLeaveHint()
    }

    




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
        
        
        val sessionId = viewModel.sessionId
        ongoingCallService.ensureStarted(sessionId)
        ongoingCallService.updateServiceType(sessionId)

        onGoingCallStateManager.setIsInForeground(true)

        if (viewModel.isRequestingPermission()) {
            viewModel.callUiController.setRequestPermissionStatus(false)
        }
        
        
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
        
        
        
        if (!isInPictureInPictureMode) {
            reapplyOrientationPolicyIfOwned()
        }
    }

    





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

    





    internal fun endCallAndClearResources() {
        viewModel.markEnding()
        runOnUiThread {
            viewModel.doExitClear()
            finishAndRemoveTask()
        }
    }

    




    fun countDownEndVibrate() {
        if (checkSelfPermission(android.Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        vibrationManager.vibrateOnce(200L, 200)
    }

    



    fun updateForegroundServiceType() {
        ongoingCallService.updateServiceType(viewModel.sessionId)
    }

    


    fun showStyledPopTip(message: String, onDismiss: () -> Unit = {}) {
        ToastUtil.show(message)
        onDismiss()
    }

    






    internal fun updateScreenshotListeningState() {
        screenshotController?.updateListeningState()
    }

    








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
