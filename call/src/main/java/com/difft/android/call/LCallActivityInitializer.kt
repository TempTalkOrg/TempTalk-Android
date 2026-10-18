package com.difft.android.call

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.manager.CallCleanupManager
import com.difft.android.call.manager.CallDialogManager
import com.difft.android.call.manager.CallLifecycleObserver
import com.difft.android.call.manager.CallScreenshotController
import com.difft.android.call.manager.PictureInPictureManager
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver
import com.difft.android.call.session.CallRoomState
import com.difft.android.call.ui.CallContent
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.base.utils.hideNavigationBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    // The orientation policy is applied pre-super in LCallActivity.onCreate (it must precede
    // the first frame); nothing orientation-related belongs here. The session shows the call
    // (`isInCalling`) from its start; the page writes only its own flags.
    if (isAppLockEnabled()) {
        onGoingCallStateManager.setNeedAppLock(callIntent.needAppLock)
    } else {
        onGoingCallStateManager.setNeedAppLock(false)
    }

    viewModel.callUiController.setPipModeEnabled(isInPictureInPictureMode)
    onGoingCallStateManager.setIsInPipMode(isInPictureInPictureMode)
}

/** Renders the session's finish request (tip, then the finish-effect end path); the view model is attached to the session. */
internal fun LCallActivity.initializeSessionEffects() {
    launchFinishRequestCollector(
        finishRequest = viewModel.finishRequest,
        showTip = { message, onDismiss -> showStyledPopTip(message, onDismiss) },
        endCall = { endCallAndClearResources() },
    )
}

/**
 * Binds the page to the resolved [LCallActivity.session]: the call it shows comes from the session,
 * the intent that opened the page only says whether its entry point already shows the wait dialog.
 * Creating the view model attaches the page to the session.
 */
internal fun LCallActivity.handleIntent(savedInstanceState: Bundle?) {
    callIntent = session.params.callIntent
    launchIntent = CallIntent(intent)
    val sessionId = viewModel.sessionId
    L.i {
        "[Call] LCallActivity: attached session id=$sessionId action=${launchIntent.action} " +
            "restored=${savedInstanceState != null}"
    }
}

internal fun LCallActivity.initializeManagers() {
    initializePictureInPictureManager()
    initializeDialogManager()
    initializeInviteCallManager()
    initializeScreenshotController()
}

internal fun LCallActivity.registerListeners() {
    registerOnBackPressedHandler()
    // Broadcast receiver registration involves Binder IPC to system_server,
    // which can take 100-500ms+ under load. Defer to the next main-looper
    // pass so onCreate returns promptly and the first frame renders.
    // NOTE: Must use Dispatchers.Main (not .immediate) — lifecycleScope
    // defaults to Main.immediate which would execute inline on the main thread.
    lifecycleScope.launch(Dispatchers.Main) {
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

/**
 * Shows the call once the session's room setup resolves: a ready room is rendered, a failed one
 * finishes the page (the session ends itself). While it is pending the page shows a wait dialog,
 * unless its entry point already shows one; both claims are released when it resolves.
 */
internal fun LCallActivity.initializeView() {
    L.i { "[Call] LCallActivity initView" }
    val pageWaitToken = if (!launchIntent.callWaitDialogShown && viewModel.roomState.value == CallRoomState.Pending) {
        CallWaitDialogUtil.show(this)
    } else {
        CallWaitDialogUtil.NO_CLAIM
    }
    // Dispatchers.Main posts, so the content is set after the rest of onCreate, as the managers expect.
    lifecycleScope.launch(Dispatchers.Main) {
        val state = viewModel.roomState.first { it != CallRoomState.Pending }
        CallWaitDialogUtil.dismiss(pageWaitToken)
        viewModel.releaseEntryWaitDialog()
        val room = (state as? CallRoomState.Ready)?.room?.takeUnless { viewModel.isSessionEnded }
        if (room == null) {
            L.i { "[Call] initView: no room to show state=${state.javaClass.simpleName}, finishing" }
            finish()
            return@launch
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
                // The session publishes the call type to OnGoingCallStateManager itself.
                onCallTypeChanged = { updateScreenshotListeningState() },
                onInviteUsersClick = { handleInviteUsersClick() },
                onWindowZoomOutClick = { handleWindowZoomOutClick() },
                onInviteViewAction = { handleInviteViewAction(it) },
                onExitClick = { params, callEndType -> handleExitClick(params, callEndType) },
                onBottomCallEndAction = { action -> handleBottomCallEndAction(action) }
            )
        }
    }
}

internal fun LCallActivity.initializePictureInPictureManager() {
    pictureInPictureManager = PictureInPictureManager(
        activity = this,
        lifecycle = lifecycle,
        scope = lifecycleScope,
        onPipModeChanged = { isInPipMode ->
            viewModel.callUiController.setPipModeEnabled(isInPipMode)
            onGoingCallStateManager.setIsInPipMode(isInPipMode)
        }
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
        callDialogManager = callDialogManager,
        callConfig = callConfig
    ).also { lifecycle.addObserver(it) }
}

internal fun LCallActivity.initializeCleanupManager() {
    callCleanupManager = CallCleanupManager(
        lifecycle = lifecycle,
        context = this,
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
    viewModel.requestExit(params, callEndType ?: CallEndType.LEAVE)
}
