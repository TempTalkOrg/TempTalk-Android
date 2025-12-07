package com.difft.android.call

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.CountdownTimer
import com.difft.android.base.user.PromptReminder
import com.difft.android.base.user.UserManager
import com.difft.android.base.user.defaultBarrageTexts
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.exception.NetworkConnectionPoorException
import com.difft.android.call.exception.StartCallException
import com.difft.android.call.service.ForegroundService
import com.difft.android.call.ui.MainPageWithBottomControlView
import com.difft.android.call.ui.MainPageWithTopStatusView
import com.difft.android.call.ui.MultiParticipantCallPage
import com.difft.android.call.ui.ShowBottomCallEndView
import com.difft.android.call.ui.ShowHandsUpBottomView
import com.difft.android.call.ui.ShowItemsBottomView
import com.difft.android.call.ui.ShowParticipantsListView
import com.difft.android.call.ui.SingleParticipantCallPage
import com.difft.android.network.config.GlobalConfigsManager
import dagger.hilt.android.AndroidEntryPoint
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.android.libraries.denoise_filter.DenoisePluginAudioProcessor
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject


@AndroidEntryPoint
class LCallActivity : AppCompatActivity() {

    @Inject
    lateinit var callToChatController: LCallToChatController

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var userManager: UserManager

    private val mySelfId: String by lazy {
        globalServices.myId
    }

    private var callType: String = ""

    private lateinit var callIntent: CallIntent

    private var conversationId: String? = null

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

    private var countdownDispose: Disposable? = null

    private val audioProcessor = DenoisePluginAudioProcessor()

    private lateinit var pipBuilderParams: PictureInPictureParams.Builder

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var proximitySensorListener: SensorEventListener? = null

    private lateinit var backPressedCallback: OnBackPressedCallback

    private var checkFloatingWindowPermissionDialog: ComposeDialog? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        L.i { "[Call] LCallActivity: onCreate" }
        callIntent = getCallIntent()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        inCalling = true

        if (isAppLockEnabled()) {
            needAppLock.set(callIntent.needAppLock)
        } else {
            needAppLock.set(false)
        }

        LCallManager.dismissWaitDialog()

        viewModel.getRoomId()?.let { setCurrentRoomId(it) }

        viewModel.callUiController.setPipModeEnabled(isInPictureInPictureMode)

        if (savedInstanceState == null) {
            if (callRole == CallRole.CALLEE) {
                LCallManager.stopIncomingCallService(callIntent.roomId, tag = "accept: has in call activity")
            }
            logIntent(callIntent)
            processIntent(callIntent)
        } else {
            L.i { "[Call] LCallActivity: Activity likely rotated, not processing intent" }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initView()

        registerCallActivityReceiver()

        initializePictureInPictureParams()

        initializeFaceFittingScreen()

        registerOnBackPressedHandler()

        registerAppUnlockListener()

        allowOnLockScreen()
    }

    private fun getCallIntent(): CallIntent {
        return CallIntent(intent)
    }

    private fun processIntent(callIntent: CallIntent) {
        if (callIntent.action == CallIntent.Action.START_CALL || callIntent.action == CallIntent.Action.JOIN_CALL) {
            if (callType.isEmpty()) {
                callType = callIntent.callType
            }
            conversationId = callIntent.conversationId
            mConversationId = conversationId
        }
    }

    private fun logIntent(callIntent: CallIntent) {
        L.d { "[Call] LCallActivity logIntent:$callIntent" }
    }

    private fun initView() {
        L.i { "[Call] LCallActivity initView" }
        setContent {
            val room = viewModel.room
            val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
            Content(
                room,
                audioSwitchHandler = viewModel.audioHandler,
                isUserSharingScreen = isUserSharingScreen
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.error.collect {
                        if (it != null) {
                            when (it) {
                                is CancellationException -> {
                                    L.e { "[call] LCallActivity, viewModel.error CancellationException: $it" }
                                    viewModel.dismissError()
                                }

                                is SocketTimeoutException -> {
                                    L.e { "[call] LCallActivity, viewModel.error SocketTimeoutException: $it" }
                                    showStyledPopTip(getString(R.string.call_connect_timeout_tip), onDismiss = { viewModel.dismissError() })
                                }

                                is NetworkConnectionPoorException -> {
                                    L.e { "[call] LCallActivity, viewModel.error ConnectionPoorException: ${it.message}" }
                                    it.message?.let { message ->
                                        showStyledPopTip(message, onDismiss = { viewModel.dismissError() })
                                    }
                                }

                                is StartCallException -> {
                                    handleStartCallException(it)
                                }

                                else -> {
                                    L.e { "[call] LCallActivity, viewModel.error connect exception: $it" }
                                    lifecycleScope.launch {
                                        delay(2000L)
                                        showStyledPopTip(getString(R.string.call_connect_exception_error), onDismiss = {
                                            endCallAndClearResources()
                                        })
                                    }
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.callUiController.isShareScreening.collect {
                        L.i { "[call] LCallActivity, viewModel.isParticipantSharedScreen: $it" }
                        LCallManager.setCallScreenSharing(it)
                    }
                }

                launch {
                    viewModel.callStatus.collect {
                        L.i { "[Call] LCallActivity callStatus:$it" }
                        if (it == CallStatus.CONNECTED) {
                            currentRoomId?.let {
                                callToChatController.cancelNotificationById(currentRoomId.hashCode())
                            }
                        }
                    }
                }

                launch {
                    viewModel.isNoSpeakSoloTimeout.collect {
                        if (it) {
                            if (!isShowCallingEndReminder) {
                                L.i { "[Call] LCallActivity Show CallingEnd Reminder" }
                                isShowCallingEndReminder = true
                                callConfig.let { config ->
                                    val waitingSeconds = config.autoLeave.runAfterReminderTimeout
                                    val secondsToLeaveMeeting = waitingSeconds / 1000
                                    showCallingEndReminder(secondsToLeaveMeeting)
                                }
                            }
                        } else {
                            if (isShowCallingEndReminder) {
                                L.i { "[Call] LCallActivity dismiss CallingEnd Reminder" }
                                dismissCallingEndReminder()
                            }
                        }
                    }
                }
            }
        }

        startOngoingCallService()
    }

    private var isShowCallingEndReminder: Boolean = false

    private var callEndReminderDialog: ComposeDialog? = null
    private var callEndReminderMessageView: android.widget.TextView? = null

    private val handler = Handler(Looper.getMainLooper())


    private fun dismissCallingEndReminder() {
        callEndReminderDialog?.dismiss()
        isShowCallingEndReminder = false
        callEndReminderDialog = null
        if (countdownDispose?.isDisposed == false) {
            countdownDispose?.dispose()
        }
    }

    @SuppressLint("AutoDispose")
    private fun showCallingEndReminder(secondsToLeaveMeeting: Long = 180L) {
        var remainingSecondsToLeaveMeeting = secondsToLeaveMeeting
        // Show dialog to remind user to speak
        if (callEndReminderDialog == null) {
            remainingSecondsToLeaveMeeting = secondsToLeaveMeeting
            val title = if (viewModel.room.remoteParticipants.isEmpty()) {
                getString(R.string.call_single_person_timeout_reminder)
            } else {
                getString(R.string.call_all_mute_timeout_reminder)
            }

            callEndReminderDialog = ComposeDialogManager.showMessageDialog(
                context = this,
                title = title,
                layoutId = R.layout.dialog_calling_end_reminder,
                cancelable = false,
                confirmText = getString(R.string.call_reminder_button_continue),
                cancelText = getString(R.string.call_reminder_button_exit),
                confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(this, com.difft.android.base.R.color.t_info)),
                cancelButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(this, com.difft.android.base.R.color.t_error_night)),
                onConfirm = {
                    viewModel.resetNoBodySpeakCheck()
                    if (viewModel.callType.value == CallType.ONE_ON_ONE.type) {
                        viewModel.room.remoteParticipants.values.firstOrNull()?.let { participant ->
                            viewModel.rtm.sendContinueCallRtmMessage(participant)
                        }
                    }
                    dismissCallingEndReminder()
                },
                onCancel = {
                    currentRoomId?.let { roomId ->
                        val callExitParams = CallExitParams(roomId, callIntent.callerId, callRole, viewModel.callType.value, conversationId)
                        handleExitClick(callExitParams)
                    }
                },
                onDismiss = {
                    if (countdownDispose?.isDisposed == false) {
                        countdownDispose?.dispose()
                    }
                },
                onViewCreated = { view ->
                    val messageView = view.findViewById<android.widget.TextView>(R.id.tv_message)
                    messageView.text = "${remainingSecondsToLeaveMeeting}s left"

                    // 保存messageView的引用，用于动态更新
                    callEndReminderMessageView = messageView
                }
            )

            if (viewModel.callUiController.isInPipMode.value) {
                val intent = CallIntent.Builder(this, LCallActivity::class.java)
                    .withAction(CallIntent.Action.BACK_TO_CALL)
                    .withIntentFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).build()

                startActivity(intent)
                handler.postDelayed({
                    remainingSecondsToLeaveMeeting = secondsToLeaveMeeting
                    callEndReminderMessageView?.text = "${remainingSecondsToLeaveMeeting}s left"
                }, 2000)
            }
        } else {
            remainingSecondsToLeaveMeeting -= 1
            callEndReminderMessageView?.text = "${remainingSecondsToLeaveMeeting}s left"
            if (remainingSecondsToLeaveMeeting <= 0) {
                endCallAndClearResources()
            }
        }

        countdownDispose = Observable.interval(1, TimeUnit.SECONDS, Schedulers.io())
            .take(secondsToLeaveMeeting + 1) // 发射 countDownSeconds + 1 次，因为区间是从 0 开始的
            .map { secondsToLeaveMeeting - it } // 将发射的数字转换为剩余的秒数
            .observeOn(AndroidSchedulers.mainThread()) // 切换到主线程来更新 UI 或执行其他操作
            .subscribe({ remainingSeconds ->
                callEndReminderMessageView?.text = "${remainingSeconds}s left"
                if (remainingSeconds <= 0) {
                    endCallAndClearResources()
                }
            }, { throwable ->
                // 处理错误
                L.e { "[Call] LCallActivity showCallingEndReminder countdownDispose error:" + throwable.message }
            }, {
                // 倒计时结束
            })

    }

    @Composable
    fun Content(
        room: Room? = null,
        audioSwitchHandler: AudioSwitchHandler? = null,
        isUserSharingScreen: Boolean = false,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val showTopStatusViewEnabled by viewModel.callUiController.showTopStatusViewEnabled.collectAsState(true)
        val showBottomToolBarViewEnabled by viewModel.callUiController.showBottomToolBarViewEnabled.collectAsState(true)
        val currentCallType by viewModel.callType.collectAsState()

        fun handleClickScreen() {
            if (viewModel.callUiController.showSimpleBarrageEnabled.value && showBottomToolBarViewEnabled) {
                viewModel.callUiController.setShowSimpleBarrageEnabled(false)
            } else {
                viewModel.callUiController.setShowTopStatusViewEnabled(!showTopStatusViewEnabled)
                viewModel.callUiController.setShowBottomToolBarViewEnabled(!showBottomToolBarViewEnabled)
            }
        }

        LaunchedEffect(currentCallType) {
            callType = currentCallType
        }

        DifftTheme(darkTheme = true) {
            ConstraintLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { handleClickScreen() }
            ) {
                val (speakerView) = createRefs()
                room?.let {
                    // 根据 callType 渲染不同的 UI
                    when (currentCallType) {
                        // 1v1 Call UI布局逻辑
                        CallType.ONE_ON_ONE.type -> {
                            Surface(
                                modifier = Modifier
                                    .constrainAs(speakerView) {
                                        top.linkTo(parent.top)
                                        start.linkTo(parent.start)
                                        end.linkTo(parent.end)
                                        bottom.linkTo(parent.bottom)
                                        width = Dimension.fillToConstraints
                                        height = Dimension.fillToConstraints
                                    },
                                color = Color.Transparent
                            ) {
                                // 1v1 Call UI布局逻辑
                                SingleParticipantCallPage(
                                    viewModel= viewModel,
                                    room = room,
                                    autoHideTimeout = autoHideTimeout,
                                    callConfig = callConfig,
                                    conversationId = conversationId,
                                    callRole = callRole
                                )
                                // 顶部和底部Control View
                                RenderTopAndBottomOverlays(isOneVOneCall = true, isUserSharingScreen, audioSwitchHandler)
                                // 底部raise hand view
                                ShowHandsUpBottomView(viewModel, onDismiss = { viewModel.callUiController.setShowHandsUpBottomViewEnabled(false) })

                                ShowItemsBottomView(viewModel, isOneVOneCall = true, onDismiss = { viewModel.callUiController.setShowToolBarBottomViewEnable(false) }, deNoiseCallBack = { enable -> audioProcessor.setEnabled(enable) }, handleInviteUsersClick = { handleInviteUsersClick() })

                                // 屏幕分享时显示参与者小列表
                                ShowParticipantsListView(viewModel, muteOtherEnabled, handleInviteUsersClick = { handleInviteUsersClick() })
                            }
                        }

                        else -> { // 多人通话逻辑
                            Surface(
                                modifier = Modifier.constrainAs(speakerView) {
                                    top.linkTo(parent.top)
                                    start.linkTo(parent.start)
                                    end.linkTo(parent.end)
                                    bottom.linkTo(parent.bottom)
                                    width = Dimension.fillToConstraints
                                    height = Dimension.fillToConstraints
                                },
                                color = Color.Transparent
                            ) {
                                // 多人 Call UI布局逻辑
                                MultiParticipantCallPage(
                                    viewModel = viewModel,
                                    room = room,
                                    muteOtherEnabled = muteOtherEnabled,
                                    autoHideTimeout = autoHideTimeout,
                                    callConfig = callConfig
                                )
                                // 顶部和底部Control View
                                RenderTopAndBottomOverlays(isOneVOneCall = false, isUserSharingScreen, audioSwitchHandler)
                                // 底部raise hand view
                                ShowHandsUpBottomView(viewModel, onDismiss = { viewModel.callUiController.setShowHandsUpBottomViewEnabled(false) })

                                ShowItemsBottomView(viewModel, isOneVOneCall = false, onDismiss = { viewModel.callUiController.setShowToolBarBottomViewEnable(false) }, deNoiseCallBack = { enable -> audioProcessor.setEnabled(enable) }, handleInviteUsersClick = { handleInviteUsersClick() })

                                ShowBottomCallEndView(
                                    viewModel,
                                    onDismiss = {
                                        viewModel.callUiController.setShowBottomCallEndViewEnable(false)
                                        viewModel.callUiController.setShowBottomToolBarViewEnabled(true)
                                    },
                                    onClickItem = { action ->
                                        handleBottomCallEndAction(action)
                                    }
                                )

                                // 屏幕分享时显示参与者小列表
                                ShowParticipantsListView(viewModel, muteOtherEnabled, handleInviteUsersClick = { handleInviteUsersClick() })
                            }
                        }
                    }
                }

            }
        }
    }


    companion object {
        private val needAppLock = AtomicBoolean(true)

        var isInForeground = false
        private var inCalling = false
        private var inCallEnding = false
        private var currentRoomId: String? = null
        private var mConversationId: String? = null
        const val ACTION_IN_CALLING_CONTROL = "ACTION_IN_CALLING_CONTROL"
        const val EXTRA_CONTROL_TYPE = "EXTRA_CONTROL_TYPE"
        const val EXTRA_PARAM_ROOM_ID = "EXTRA_PARAM_ROOM_ID"
        private const val DEFAULT_FONT_SCALE = 1.0f

        fun isInCalling(): Boolean {
            return inCalling
        }

        /**
         * Returns whether the call is in the process of ending.
         * This state is true between call termination initiation and final cleanup.
         */
        fun isInCallEnding(): Boolean {
            return inCallEnding
        }

        fun getCurrentRoomId(): String? {
            return currentRoomId
        }

        fun setCurrentRoomId(roomId: String?) {
            currentRoomId = roomId
        }

        fun getConversationId(): String? {
            return mConversationId
        }

        fun isNeedAppLock(): Boolean = needAppLock.get()
    }

    private fun showErrorAndFinish(tips: String) {
        ToastUtil.show(tips)
        endCallAndClearResources()
    }


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

    override fun onDestroy() {
        super.onDestroy()
        L.i { "[Call] LCallActivity onDestroy start." }
        checkFloatingWindowPermissionDialog?.dismiss()
        checkFloatingWindowPermissionDialog = null

        countdownDispose?.takeIf { !it.isDisposed }?.dispose()
        callEndReminderDialog?.apply {
            dismiss()
            this@LCallActivity.callEndReminderDialog = null
        }

        LCallManager.stopRingTone()
        LCallManager.stopVibration()
        unregisterReceiver(lCallActivityReceiver)
        LCallManager.clearContactorCache()
        LCallManager.clearControlMessage()
        LCallManager.setCallScreenSharing(false)

        stopOngoingCallService()

        inCalling = false
        inCallEnding = false
        currentRoomId = null
        isInForeground = false
        needAppLock.set(true)
        audioProcessor.release()

        proximitySensorListener = null

        viewModel.getRoomId()?.let { roomId ->
            LCallManager.updateCallingState(roomId, false)
        }

        if (::backPressedCallback.isInitialized) {
            backPressedCallback.remove()
        }

        sendDeclineCallBroadcast()

        AppLockCallbackManager.removeListener(callbackId)

        L.i { "[Call] LCallActivity onDestroy end." }
    }

    private fun sendDeclineCallBroadcast() {
        try {
            Intent(ACTION_IN_CALLING_CONTROL).apply {
                putExtra(
                    EXTRA_CONTROL_TYPE,
                    CallActionType.DECLINE.type
                )
                setPackage(packageName)
                sendBroadcast(this)
            }
        } catch (e: IllegalStateException) {
            L.w(e) { "[Call] Failed to send decline broadcast during destroy" }
        }
    }

    private fun endCallAndClearResources() {
        runOnUiThread {
            inCallEnding = true
            viewModel.doExitClear()
            finishAndRemoveTask()
        }
    }

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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(
            isInPictureInPictureMode, newConfig
        )

        L.i { "[Call] LCallActivity onPictureInPictureModeChanged isInPictureInPictureMode:$isInPictureInPictureMode" }
        runOnUiThread {
            viewModel.callUiController.setPipModeEnabled(isInPictureInPictureMode)
        }

        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            if (!isInPictureInPictureMode) {

                // 20241003@w ------------------------------------------------->
                // Purpose: Don't terminate the call when the user clicks the PIP "Close" button and the screen is locked
                // Link to issue: https://github.com/difftim/difft-android/issues/1332
                if (LCallManager.isScreenLocked(this)) {
                    L.i { "[Call] LCallActivity onPictureInPictureModeChanged - Lifecycle.State.CREATED - Screen is locked" }
                } else {
                    //when user click on Close button of PIP this will trigger, do what you want here
//                    hangUpTheCall(tag = "onPictureInPictureModeChanged - Lifecycle.State.CREATED")
                    L.i { "[Call] LCallActivity onPictureInPictureModeChanged - not ScreenLocked handleExitClick " }
                    currentRoomId?.let { roomId ->
                        val callExitParams = CallExitParams(roomId, callIntent.callerId, callRole, viewModel.callType.value, conversationId)
                        handleExitClick(callExitParams)
                    }

                }
                // 20241003@w -------------------------------------------------<
            }
        } else if (lifecycle.currentState == Lifecycle.State.STARTED) {
            //when PIP maximize this will trigger
            L.d { "[Call] LCallActivity onPictureInPictureModeChanged - Lifecycle.State.STARTED" }
        }

    }

    private fun registerAppUnlockListener() {
        appUnlockListener = {
            if (it) {
                // 用户刚刚通过应用锁解锁
                needAppLock.set(false)
            }
        }
        AppLockCallbackManager.addListener(callbackId, appUnlockListener)
    }

    @SuppressLint("WrongConstant")
    private fun registerCallActivityReceiver() {
        val filter = IntentFilter()
        filter.addAction(LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT)
        filter.addAction(LCallConstants.CALL_ONGOING_TIMEOUT)
        filter.addAction(ACTION_IN_CALLING_CONTROL)
        ContextCompat.registerReceiver(this, lCallActivityReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val lCallActivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT -> {
                    L.i { "[Call] LCallActivity lCallActivityReceiver CALL_NOTIFICATION_PUSH_STREAM_LIMIT" }
                    showStyledPopTip(getString(R.string.call_push_stream_limit_tip), onDismiss = {})
                }

                LCallConstants.CALL_ONGOING_TIMEOUT -> {
                    L.i { "[Call] LCallActivity lCallActivityReceiver CALL_ONGOING_TIMEOUT" }
                    val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_ROOM_ID)
                    if (roomId != null && roomId == currentRoomId) {
                        showStyledPopTip(getString(R.string.call_callee_action_noanswer), onDismiss = { endCallAndClearResources() })
                    }
                }

                ACTION_IN_CALLING_CONTROL -> {
                    val controlType = intent.getStringExtra(EXTRA_CONTROL_TYPE) ?: ""
                    val roomId = intent.getStringExtra(EXTRA_PARAM_ROOM_ID) ?: ""
                    handleCallAction(controlType, roomId)
                }
            }
        }
    }


    private fun handleCallAction(actionType: String, roomId: String) {
        L.i { "[Call] LCallActivity handleCallAction actionType:$actionType roomId:${roomId}" }
        if (actionType == CallActionType.DECLINE.type) {
            currentRoomId?.let { roomId ->
                val callExitParams = CallExitParams(roomId, callIntent.callerId, callRole, viewModel.callType.value, conversationId)
                handleExitClick(callExitParams)
            }
            return
        }
        if (!inCallEnding && isInCalling() && roomId == currentRoomId) {
            inCallEnding = true
            when (actionType) {
                CallActionType.CALLEND.type, CallActionType.REJECT.type -> {
                    stopOngoingCallService()
                    if (shouldStopCallerRingTone()) {
                        LCallManager.stopRingTone(tag = "reject: callee reject the call")
                    }
                    LCallManager.playHangupRingTone(viewModel)
                    val messageTip = if (actionType == CallActionType.REJECT.type) {
                        R.string.call_callee_action_reject
                    } else {
                        R.string.call_callee_action_hangup
                    }
                    showStyledPopTip(getString(messageTip), onDismiss = { endCallAndClearResources() })
                }

                CallActionType.HANGUP.type -> {
                    stopOngoingCallService()
                    LCallManager.playHangupRingTone(viewModel)
                    showStyledPopTip(getString(R.string.call_callee_action_hangup), onDismiss = { endCallAndClearResources() })
                }
            }
        }
    }

    fun showStyledPopTip(message: String, onDismiss: () -> Unit = {}) {
        ToastUtil.show(message)
        onDismiss()
    }


    private fun shouldStopCallerRingTone() = callRole == CallRole.CALLER && viewModel.timerManager.callDurationSeconds.value == 0L


    @Composable
    fun RenderTopAndBottomOverlays(isOneVOneCall: Boolean, isUserSharingScreen: Boolean, audioSwitchHandler: AudioSwitchHandler?) {
        val showTopStatusViewEnabled by viewModel.callUiController.showTopStatusViewEnabled.collectAsState(true)
        val showBottomToolBarViewEnabled by viewModel.callUiController.showBottomToolBarViewEnabled.collectAsState(true)
        val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)

        // 顶部悬浮控件布局逻辑
        MainPageWithTopStatusView(viewModel, isInPipMode, isOneVOneCall, showTopStatusViewEnabled, isUserSharingScreen, callConfig, callIntent, windowZoomOutAction = {
            handleWindowZoomOutClick()
        })

        if (!isInPipMode) {
            // 底部悬浮控件布局逻辑
            MainPageWithBottomControlView(viewModel, isOneVOneCall, showBottomToolBarViewEnabled, isUserSharingScreen, audioSwitchHandler, endCallAction = { callType, callEndType ->
                val callExitParams = CallExitParams(
                    viewModel.getRoomId(),
                    callIntent.callerId,
                    callRole,
                    callType,
                    conversationId
                )
                handleExitClick(callExitParams, callEndType)
            })
        }
    }

    private fun sendCancelCallMessage(onComplete: () -> Unit) {
        L.d { "[Call] LCallActivity sendCancelCallMessage" }
        currentRoomId?.let { roomId ->
            callToChatController.cancelCall(callIntent.callerId, callRole, viewModel.callType.value, roomId, conversationId) {
                onComplete()
            }
        }
    }

    private fun handleExitClick(
        params: CallExitParams,
        callEndType: LCallManager.CallEndType? = LCallManager.CallEndType.LEAVE,
    ) {
        // 尝试获取通话列表中的通话信息
        val callInfo = LCallManager.getCallListData()?.get(params.roomId)?.let { callData ->
            callData.copy(type = callData.type)
        }

        // 如果通话信息不存在，或会议重连中，则直接结束通话并清理资源
        if (callInfo == null || viewModel.callStatus.value == CallStatus.RECONNECTING) {
            endCallAndClearResources()
            return
        }

        // 检查通话类型，如果是非1v1通话，则直接结束通话并清理资源
        if (callInfo.type != CallType.ONE_ON_ONE.type && callEndType == LCallManager.CallEndType.LEAVE) {
            endCallAndClearResources()
            return
        }

        viewModel.callStatus.value.let { status ->
            when (status) {
                CallStatus.CALLING -> {
                    // 主叫：发送取消消息
                    sendCancelCallMessage {
                        // 取消回调（结束后清理资源）
                        endCallAndClearResources()
                    }
                }

                CallStatus.JOINING -> {
                    // 被叫或其他角色：直接结束通话
                    endCallAndClearResources()
                }

                CallStatus.CONNECTED, CallStatus.RECONNECTED -> {
                    // 通话已连接：发送挂断消息
                    LCallManager.removeCallData(params.roomId)
                    L.i { "[Call] LCallActivity send hangUpCall CallMessage roomId:${params.roomId}" }
                    viewModel.rtm.sendEndCall(onComplete = {
                        if (params.roomId.isNullOrEmpty() || conversationId.isNullOrEmpty()) {
                            endCallAndClearResources()
                            return@sendEndCall
                        }
                        callToChatController.hangUpCall(params.callerId, callRole, callType, params.roomId, conversationId, viewModel.getCurrentCallUidList(), onComplete = { endCallAndClearResources() })
                    })
                }

                else -> {
                    // 其他状态：直接结束通话并清理资源
                    endCallAndClearResources()
                }
            }
        }
    }

    private fun handleInviteUsersClick() {
        L.d { "[Call] LCallActivity handleInviteUsersClick" }
        lifecycleScope.launch {
            val excludedIds = mutableListOf<String>()
            // 排除自己
            callToChatController.getMySelfUid().let {
                excludedIds.add(it)
            }
            // 排除已经在会议中的参与者
            val remoteParticipants = viewModel.room.remoteParticipants.keys.sortedBy { it.value }
            remoteParticipants.forEach { remoteParticipant ->
                remoteParticipant.value.let {
                    var userId = it
                    if (userId.contains(".")) {
                        userId = userId.split(".")[0]
                    }
                    excludedIds.add(userId)
                }
            }

            enterPipModeIfPossible(tag = "onInvitePeople")

            if (viewModel.callType.value == CallType.ONE_ON_ONE.type || viewModel.callType.value == CallType.INSTANT.type) {
                val mySelfName = withContext(Dispatchers.Default) {
                    LCallManager.getDisplayNameById(mySelfId) ?: mySelfId
                }
                val roomName = "${mySelfName}${getString(R.string.call_instant_call_title)}"
                currentRoomId?.let { roomId ->
                    callToChatController.inviteUsersToTheCall(this@LCallActivity, roomId, roomName, viewModel.getE2eeKey(), CallType.INSTANT.type, conversationId, ArrayList(excludedIds))
                }
            } else {
                currentRoomId?.let { roomId ->
                    callToChatController.inviteUsersToTheCall(this@LCallActivity, roomId, callIntent.roomName, viewModel.getE2eeKey(), CallType.GROUP.type, conversationId, ArrayList(excludedIds))
                }
            }
        }
    }

    private fun handleWindowZoomOutClick() {
        L.d { "[Call] LCallActivity handleWindowZoomOutClick" }
        // Check the “Display over other apps” permission
        if (!Settings.canDrawOverlays(this)) {
            showPipPermissionToastOrEnterPipMode("windowZoomOut")
            return
        }

        if (isSystemPipEnabledAndAvailable()) {
            enterPipModeIfPossible(tag = "windowZoomOut")
        } else {
            AlertDialog.Builder(this)
                .setMessage(R.string.call_pip_not_supported_message)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                }.show()
        }
    }

    private fun updateOngoingCallNotification(useCallStyle: Boolean) {
        L.i { "[Call] LCallActivity service updateOngoingCallNotification" }
        if (!ForegroundService.isServiceRunning) {
            L.i { "[Call] LCallActivity service updateOngoingCallNotification - ForegroundService is not running" }
            return
        }
        try {
            val intent = Intent(this, ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_UPDATE_NOTIFICATION
                putExtra(ForegroundService.EXTRA_USE_CALL_STYLE, useCallStyle)
            }
            callToChatController.startForegroundService(this, intent)
        } catch (e: Exception) {
            L.e { "[Call] LCallActivity service updateOngoingCallNotification Failed to update ongoing call notification: ${e.message}" }
        }
    }

    /**
     * Updates the foreground service type when permissions are granted after service start.
     * This ensures the service can use microphone/camera in the background.
     */
    fun updateForegroundServiceType() {
        L.i { "[Call] LCallActivity updateForegroundServiceType" }
        if (!ForegroundService.isServiceRunning) {
            L.i { "[Call] LCallActivity updateForegroundServiceType - ForegroundService is not running" }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(this, ForegroundService::class.java).apply {
                    action = ForegroundService.ACTION_UPDATE_SERVICE_TYPE
                }
                callToChatController.startForegroundService(this, intent)
            } catch (e: Exception) {
                L.e { "[Call] LCallActivity Failed to update foreground service type: ${e.message}" }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        val callIntent = getCallIntent()
        L.d { "[Call] LCallActivity onNewIntent:$callIntent" }
        super.onNewIntent(intent)
        logIntent(callIntent)
        processIntent(callIntent)
    }

    override fun onPause() {
        super.onPause()
        L.i { "[Call] LCallActivity onPause" }
        isInForeground = false
        if (!inCallEnding) {
            updateOngoingCallNotification(true)
        }

        sensorManager.unregisterListener(proximitySensorListener)
    }

    override fun onResume() {
        super.onResume()
        L.i { "[Call] LCallActivity onResume" }
        // Update foreground service type when returning from settings or other activities
        // This ensures service type is updated if permissions were granted while away
        if (ForegroundService.isServiceRunning && isInCalling()) {
            updateForegroundServiceType()
        }

        proximitySensor?.let {
            sensorManager.registerListener(
                proximitySensorListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        isInForeground = true
        updateOngoingCallNotification(false)

        if (viewModel.isRequestingPermission()) {
            viewModel.callUiController.setRequestPermissionStatus(false)
        }
    }


    private fun isSystemPipEnabledAndAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun tryToSetPictureInPictureParams() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                setPictureInPictureParams(pipBuilderParams.build())
            } catch (e: Exception) {
                L.i { "[call] tryToSetPictureInPictureParams System lied about having PiP available. $e" }
            }
        }
    }

    private fun initializePictureInPictureParams() {
        if (isSystemPipEnabledAndAvailable()) {
            val aspectRatio = Rational(16, 9)
            pipBuilderParams = PictureInPictureParams.Builder()
            pipBuilderParams.setAspectRatio(aspectRatio)

            if (Build.VERSION.SDK_INT >= 31) {
                lifecycleScope.launch {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        launch {
                            pipBuilderParams.setAutoEnterEnabled(false)
                            tryToSetPictureInPictureParams()
                        }
                    }
                }
            } else {
                tryToSetPictureInPictureParams()
            }
        }
    }

    private fun enterPipModeIfPossible(tag: String? = null): Boolean {
        L.i { "[Call] LCallActivity enterPipModeIfPossible tag:$tag" }
        if (isSystemPipEnabledAndAvailable()) {
            try {
                enterPictureInPictureMode(pipBuilderParams.build())
            } catch (e: Exception) {
                L.i { "[Call] enterPipModeIfPossible Device lied to us about supporting PiP, $e" }
                return false
            }
            return true
        }
        return false
    }

    fun countDownEndVibrate() {
        if (checkSelfPermission(android.Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LCallManager.getVibratorService().vibrate(VibrationEffect.createOneShot(200L, 200))
        } else {
            val pattern = longArrayOf(200L)
            LCallManager.getVibratorService().vibrate(pattern, -1)
        }
    }

    private fun createCallExitParams() = CallExitParams(
        viewModel.getRoomId(),
        callIntent.callerId,
        callRole,
        callType,
        conversationId
    )

    private fun handleBottomCallEndAction(action: BottomCallEndAction) {
        when (action) {
            BottomCallEndAction.END_CALL -> {
                L.i { "[call] LCallActivity onClick End" }
                handleExitClick(
                    createCallExitParams(),
                    LCallManager.CallEndType.END
                )
                viewModel.callUiController.setShowBottomCallEndViewEnable(false)
            }

            BottomCallEndAction.LEAVE_CALL -> {
                L.i { "[call] LCallActivity onClick Leave" }
                handleExitClick(
                    createCallExitParams(),
                    LCallManager.CallEndType.LEAVE
                )
                viewModel.callUiController.setShowBottomCallEndViewEnable(false)
            }

            else -> {
                viewModel.callUiController.setShowBottomCallEndViewEnable(false)
                viewModel.callUiController.setShowBottomToolBarViewEnabled(true)
            }
        }
    }

    /**
     * Initializes proximity sensor to detect when phone is near face during calls
     * to disable screen touches and dim display to prevent accidental input
     */
    private fun initializeFaceFittingScreen() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (proximitySensor == null) {
            L.i { "[Call]: Proximity sensor not available" }
        } else {
            proximitySensorListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    event?.let {
                        val distance = it.values[0]

                        val sensor = proximitySensor ?: return
                        val maximumRange = sensor.maximumRange
                        L.d { "[Call]: onSensorChanged distance=$distance, maximumRange=$maximumRange" }

                        if (distance < maximumRange) {
                            if (viewModel.callUiController.isShareScreening.value) {
                                L.i { "[Call]: onSensorChanged - in screen sharing, return" }
                                return // 当在屏幕共享时，不处理
                            }
                            disableWindowForTouchAndKeepScreenOn()
                        } else {
                            enableWindowForTouchAndKeepScreenOn()
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                }
            }
        }
    }

    /**
     * Called when the device is close to the user's face to disable touch and keep screen on
     * 1. Add FLAG_NOT_TOUCHABLE to disable all touch events
     * 2. Keep screen on (FLAG_KEEP_SCREEN_ON)
     * 3. Set screen brightness to minimum (0.0f)
     */
    private fun disableWindowForTouchAndKeepScreenOn() {
        // disable touch
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // adjust the screen brightness to minimum
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = 0.0f
        }
    }

    /**
     * This method is called when the device is moved away from the user to restore normal screen interaction and brightness settings.
     * 1. Clear the window's not-touchable flag (FLAG_NOT_TOUCHABLE) to restore touch functionality.
     * 2. Keep the screen on (FLAG_KEEP_SCREEN_ON).
     * 3. Restore the screen brightness to the system default value (BRIGHTNESS_OVERRIDE_NONE).
     */
    private fun enableWindowForTouchAndKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun startOngoingCallService() {
        L.i { "[Call] LCallActivity service startOngoingCallService" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            try {
                callToChatController.startForegroundService(this, serviceIntent)
            } catch (e: Exception) {
                L.e { "[Call] LCallActivity service Failed to start foreground service: ${e.message}" }
            }
        } else {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            startService(serviceIntent)
        }
    }

    private fun stopOngoingCallService() {
        if (!ForegroundService.isServiceRunning) {
            return
        }
        L.i { "[Call] LCallActivity service stopOngoingCallService" }
        try {
            val serviceIntent = Intent(application, ForegroundService::class.java)
            application.stopService(serviceIntent)
        } catch (e: Exception) {
            L.e { "[Call] LCallActivity service Failed to stop ongoing call service: ${e.message}" }
        }
    }

    private fun handleStartCallException(exception: Exception) {
        L.e { "[call] LCallActivity, viewModel.error StartCallException: ${exception.message}" }
        val tipTextInfo = if (callIntent.action == CallIntent.Action.START_CALL)
            getString(R.string.call_start_failed_tip)
        else
            getString(R.string.call_join_failed_tip)

        showStyledPopTip(tipTextInfo, onDismiss = { endCallAndClearResources() })
    }

    private fun registerOnBackPressedHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                L.i { "[Call] LCallActivity intercept on back press" }
                showPipPermissionToastOrEnterPipMode("back pressed")
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    private fun showPipPermissionToastOrEnterPipMode(tag: String?) {
        val currentVersion = userManager.getUserData()?.floatingWindowPermissionTipsShowedVersion
        if (currentVersion == PackageUtil.getAppVersionName() || Settings.canDrawOverlays(this)) {
            enterPipModeIfPossible(tag)
            return
        }

        if (checkFloatingWindowPermissionDialog == null) {
            checkFloatingWindowPermissionDialog = ComposeDialogManager.showMessageDialog(
                context = this,
                cancelable = false,
                title = getString(R.string.call_pip_no_permission_tip_title),
                message = getString(R.string.call_pip_permission_tip_content),
                confirmText = getString(R.string.call_permission_button_setting_go),
                cancelText = getString(R.string.call_permission_button_not_now),
                onConfirm = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        ToastUtil.show(getString(R.string.call_pip_permission_setting_failed))
                    }
                },
                onCancel = {
                    // 用户选择“暂不开启” → 记录当前版本，避免重复弹窗
                    lifecycleScope.launch(Dispatchers.IO) {
                        userManager.update {
                            floatingWindowPermissionTipsShowedVersion = PackageUtil.getAppVersionName()
                        }
                    }
                    enterPipModeIfPossible(tag)
                },
                onDismiss = {
                    checkFloatingWindowPermissionDialog = null
                }
            )
        } else {
            checkFloatingWindowPermissionDialog?.dismiss()
            checkFloatingWindowPermissionDialog = null
        }
    }

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

    private fun isAppLockEnabled(): Boolean {
        if (!::userManager.isInitialized) return false
        val user = userManager.getUserData() ?: return false
        val hasPattern = !user.pattern.isNullOrEmpty()
        val hasPasscode = !user.passcode.isNullOrEmpty()
        return hasPattern || hasPasscode
    }
}
