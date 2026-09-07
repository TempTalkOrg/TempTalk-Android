package com.difft.android.call

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.base.utils.OrientationPolicy
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.handler.CallActionHandler
import com.difft.android.call.handler.CallErrorHandler
import com.difft.android.call.handler.CallExitHandler
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.manager.CallCleanupManager
import com.difft.android.call.manager.CallDialogManager
import com.difft.android.call.manager.CallLifecycleObserver
import com.difft.android.call.manager.CallScreenshotController
import com.difft.android.call.manager.CallServiceManager
import com.difft.android.call.manager.PictureInPictureManager
import com.difft.android.call.manager.ProximitySensorManager
import com.difft.android.call.receiver.CallActivityBroadcastReceiver
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver
import com.difft.android.call.ui.CallContent
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.base.utils.hideNavigationBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Initialization extensions for [LCallActivity].
 *
 * Splits the bulky initializeXxx / registerXxx wiring from [LCallActivity]
 * into extension functions so the Activity body focuses only on lifecycle
 * scheduling and high-level coordination.
 *
 * All extensions depend solely on the Activity's exposed internal members
 * and preserve the original business semantics unchanged.
 */

internal fun LCallActivity.initializeState() {
    // sw600dp orientation policy (portrait on phones, free rotation on tablets / unfolded
    // foldables) — LCallActivity is not a BaseActivity, so apply it directly. Overrides the
    // manifest portrait lock on large screens, same as every policy-managed activity.
    policyAppliedOrientation = OrientationPolicy.applyTo(this)
    onGoingCallStateManager.setIsInCalling(true)

    if (isAppLockEnabled()) {
        onGoingCallStateManager.setNeedAppLock(callIntent.needAppLock)
    } else {
        onGoingCallStateManager.setNeedAppLock(false)
    }

    viewModel.getRoomId()?.let {
        onGoingCallStateManager.setCurrentRoomId(it)
    }

    viewModel.callUiController.setPipModeEnabled(isInPictureInPictureMode)
    onGoingCallStateManager.setIsInPipMode(isInPictureInPictureMode)
}

internal fun LCallActivity.initializeErrorHandler() {
    callErrorHandler = CallErrorHandler(
        activity = this,
        lifecycleScope = lifecycleScope,
        callIntent = callIntent,
        onEndCall = { endCallAndClearResources() },
    )
}

internal fun LCallActivity.initializeExitHandler() {
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

internal fun LCallActivity.initializeCallActionHandler() {
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

internal fun LCallActivity.handleIntent(savedInstanceState: Bundle?) {
    callIntent = CallIntent(intent)
    if (savedInstanceState == null) {
        L.i { "[Call] LCallActivity: Processing intent" }
        if (callRole == CallRole.CALLEE) {
            LCallManager.stopIncomingCallService(callIntent.roomId, tag = "accept: has in call activity")
        } else if (callIntent.action == CallIntent.Action.START_CALL && callIntent.callType == CallType.ONE_ON_ONE.type) {
            callIntent.conversationId?.let { conversationId ->
                viewModel.addAwaitingJoinInvitees(listOf(conversationId))
            }
        }
        L.d { "[Call] LCallActivity logIntent:$callIntent" }
        processIntent(callIntent)
    } else {
        L.i { "[Call] LCallActivity: Activity likely rotated, not processing intent" }
    }
}

internal fun LCallActivity.processIntent(callIntent: CallIntent) {
    if (callIntent.action == CallIntent.Action.START_CALL || callIntent.action == CallIntent.Action.JOIN_CALL) {
        if (onGoingCallStateManager.callType().isEmpty()) {
            onGoingCallStateManager.setCallType(callIntent.callType)
        }
        onGoingCallStateManager.setConversationId(callIntent.conversationId)
        // Only the start-call path carries a clientCallId; join carries null and leaves it unset.
        callIntent.clientCallId?.let { onGoingCallStateManager.setClientCallId(it) }
    }
}

internal fun LCallActivity.initializeManagers() {
    initializeCallServiceManager()
    initializePictureInPictureManager()
    initializeProximitySensor()
    initializeDialogManager()
    initializeInviteCallManager()
    initializeScreenshotController()
}

internal fun LCallActivity.registerListeners() {
    registerOnBackPressedHandler()
    registerAppUnlockListener()
    // Broadcast receiver registration involves Binder IPC to system_server,
    // which can take 100-500ms+ under load. Defer to the next main-looper
    // pass so onCreate returns promptly and the first frame renders.
    // NOTE: Must use Dispatchers.Main (not .immediate) — lifecycleScope
    // defaults to Main.immediate which would execute inline on the main thread.
    lifecycleScope.launch(Dispatchers.Main) {
        registerCallActivityReceiver()
        registerScreenUnlockReceiver()
    }
}

internal fun LCallActivity.configureWindow() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    allowOnLockScreen()
    window.hideNavigationBar()
    @Suppress("DEPRECATION")
    window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
        if (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
            window.hideNavigationBar()
        }
    }
}

internal fun LCallActivity.initializeView() {
    L.i { "[Call] LCallActivity initView" }
    if (!callIntent.callWaitDialogShown) {
        CallWaitDialogUtil.show(this)
    }
    lifecycleScope.launch {
        // Phase B can throw if a release races in mid-wiring (fail-loud room getter). Catch it so the
        // dismiss below ALWAYS runs — otherwise the wait dialog would stick on screen forever.
        val wiringOk = withContext(Dispatchers.Default) {
            runCatching { viewModel.startRoomDependentWiring() }
                .onFailure { L.w(it) { "[Call] startRoomDependentWiring aborted (release in flight)" } }
                .isSuccess
        }
        withContext(Dispatchers.Main) {
            CallWaitDialogUtil.dismiss()
            // Read the room once, defensively: if wiring aborted or the room was released, finish
            // gracefully instead of feeding a released room into setContent.
            val room = if (wiringOk) runCatching { viewModel.room }.getOrNull() else null
            if (room == null) {
                L.i { "[Call] initView: room unavailable (release in flight), finishing" }
                finish()
                return@withContext
            }
            setContent {
                val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
                CallContent(
                    room = room,
                    viewModel = viewModel,
                    inviteCallHandler = inviteCallManager,
                    isUserSharingScreen = isUserSharingScreen,
                    callConfig = callConfig,
                    callIntent = callIntent,
                    callRole = callRole,
                    conversationId = onGoingCallStateManager.getConversationId(),
                    autoHideTimeout = autoHideTimeout,
                    muteOtherEnabled = muteOtherEnabled,
                    onScreenClick = { handleScreenClick() },
                    onCallTypeChanged = {
                        onGoingCallStateManager.setCallType(it)
                        updateScreenshotListeningState()
                    },
                    onInviteUsersClick = { handleInviteUsersClick() },
                    onWindowZoomOutClick = { handleWindowZoomOutClick() },
                    onInviteViewAction = { handleInviteViewAction(it) },
                    onExitClick = { params, callEndType -> handleExitClick(params, callEndType) },
                    onBottomCallEndAction = { action -> handleBottomCallEndAction(action) }
                )
            }
        }
    }
}

internal fun LCallActivity.initializeCallServiceManager() {
    callServiceManager = CallServiceManager(
        context = this,
        callToChatController = callToChatController
    ).also { it.startOngoingCallService() }
}

internal fun LCallActivity.initializePictureInPictureManager() {
    pictureInPictureManager = PictureInPictureManager(
        activity = this,
        lifecycle = lifecycle,
        scope = lifecycleScope,
        onPipModeChanged = { isInPipMode ->
            viewModel.callUiController.setPipModeEnabled(isInPipMode)
            onGoingCallStateManager.setIsInPipMode(isInPipMode)
        },
        onPipClosed = {
            onGoingCallStateManager.getCurrentRoomId()?.let { roomId ->
                handleExitClick(
                    CallExitParams(
                        roomId,
                        callIntent.callerId,
                        callRole,
                        onGoingCallStateManager.callType(),
                        onGoingCallStateManager.getConversationId()
                    )
                )
            }
        }
    ).also { it.initialize() }
}

internal fun LCallActivity.initializeProximitySensor() {
    proximitySensorManager = ProximitySensorManager(
        activity = this,
        isScreenSharingProvider = { viewModel.callUiController.isShareScreening.value }
    ).also { it.initialize() }
}

internal fun LCallActivity.initializeDialogManager() {
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

internal fun LCallActivity.initializeInviteCallManager() {
    inviteCallManager = InviteCallHandler(
        viewModel = viewModel,
        callToChatController = callToChatController,
        contactorCacheManager = contactorCacheManager,
        callIntent = callIntent,
        scope = lifecycleScope
    )
}

internal fun LCallActivity.initializeLifecycleObserver() {
    callLifecycleObserver = CallLifecycleObserver(
        viewModel = viewModel,
        onGoingCallStateManager = onGoingCallStateManager,
        callToChatController = callToChatController,
        callErrorHandler = callErrorHandler,
        callDialogManager = callDialogManager,
        callConfig = callConfig
    ).also { lifecycle.addObserver(it) }
}

internal fun LCallActivity.initializeCleanupManager() {
    callCleanupManager = CallCleanupManager(
        lifecycle = lifecycle,
        context = this,
        callbackId = callbackId
    )
}

internal fun LCallActivity.initializeScreenshotController() {
    screenshotController = CallScreenshotController(
        activity = this,
        coroutineScope = lifecycleScope,
        onGoingCallStateManager = onGoingCallStateManager,
        callToChatController = callToChatController,
        conversationIdProvider = { callIntent.conversationId },
        callTypeProvider = {
            onGoingCallStateManager.callType().ifEmpty { callIntent.callType }
        },
        isInPipModeProvider = {
            onGoingCallStateManager.isInPipMode() || isInPictureInPictureMode
        },
        focusLostAtProvider = { windowFocusLostAt }
    )
}

internal fun LCallActivity.registerAppUnlockListener() {
    appUnlockListener = {
        if (it) {
            onGoingCallStateManager.setNeedAppLock(false)
        }
    }
    AppLockCallbackManager.addListener(callbackId, appUnlockListener)
}

internal fun LCallActivity.registerCallActivityReceiver() {
    callActivityBroadcastReceiver = CallActivityBroadcastReceiver(
        onPushStreamLimit = {
            showStyledPopTip(getString(R.string.call_push_stream_limit_tip), onDismiss = {})
        },
        onOngoingTimeout = { roomId ->
            if (roomId == onGoingCallStateManager.getCurrentRoomId()) {
                showStyledPopTip(
                    getString(R.string.call_callee_action_noanswer),
                    onDismiss = { endCallAndClearResources() })
            }
        },
        onCallControl = { actionType, roomId ->
            callActionHandler?.handleCallAction(actionType, roomId)
        }
    ).also { it.register(this) }
}

internal fun LCallActivity.registerScreenUnlockReceiver() {
    screenUnlockBroadcastReceiver = ScreenUnlockBroadcastReceiver(
        onBringCallActivityToFront = { LCallManager.bringCallScreenToFront(this) },
        onGoingCallStateManager = onGoingCallStateManager
    ).also { it.register(this) }
}

internal fun LCallActivity.registerOnBackPressedHandler() {
    backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            L.i { "[Call] LCallActivity intercept on back press" }
            showPipPermissionToastOrEnterPipMode("back pressed")
        }
    }
    onBackPressedDispatcher.addCallback(this, backPressedCallback)
}

internal fun LCallActivity.createCallExitParams(): CallExitParams = CallExitParams(
    viewModel.getRoomId(),
    callIntent.callerId,
    callRole,
    onGoingCallStateManager.callType(),
    onGoingCallStateManager.getConversationId()
)

internal fun LCallActivity.handleExitClick(
    params: CallExitParams,
    callEndType: CallEndType? = CallEndType.LEAVE,
) {
    callExitHandler?.handleExit(params, callEndType ?: CallEndType.LEAVE)
        ?: run {
            L.w { "[Call] LCallActivity: CallExitHandler is not initialized, ending call directly" }
            endCallAndClearResources()
        }
}
