/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.colorResource
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.AppTheme
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.CountdownTimer
import com.difft.android.base.user.PromptReminder
import com.difft.android.base.user.defaultBarrageTexts
import com.difft.android.base.utils.globalServices
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.exception.NetworkConnectionPoorException
import com.difft.android.call.exception.StartCallException
import com.difft.android.call.service.ForegroundService
import com.difft.android.call.ui.MainPageWithBottomControlView
import com.difft.android.call.ui.MainPageWithTopStatusView
import com.difft.android.call.ui.ShowBottomCallEndView
import com.difft.android.call.ui.ShowHandsUpBottomView
import com.difft.android.call.ui.ShowItemsBottomView
import com.difft.android.network.config.GlobalConfigsManager
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.interfaces.DialogLifecycleCallback
import com.kongzue.dialogx.util.TextInfo
import dagger.hilt.android.AndroidEntryPoint
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.*
import org.difft.android.libraries.denoise_filter.DenoisePluginAudioProcessor
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject


@AndroidEntryPoint
class LCallActivity : AppCompatActivity() {

    @Inject
    lateinit var callToChatController: LCallToChatController

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    private val mySelfId: String by lazy {
        globalServices.myId
    }

    private var callType: String = ""

    private lateinit var callIntent: CallIntent

    private var conversationId: String? = null

    private val callRole: CallRole by lazy {
        if(callIntent.callRole == CallRole.CALLER.type) CallRole.CALLER else CallRole.CALLEE
    }

    private val callConfig: CallConfig by lazy {
        globalConfigsManager.getNewGlobalConfigs()?.data?.call?: CallConfig(autoLeave = AutoLeave(promptReminder = PromptReminder()), chatPresets = defaultBarrageTexts, chat = CallChat(), countdownTimer = CountdownTimer())
    }

    private val autoHideTimeout:Long by lazy {
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
        LCallManager.dismissWaitDialog()

        viewModel.getRoomId()?.let { setCurrentRoomId(it) }

        viewModel.setPipModeEnabled(isInPictureInPictureMode)

        if (savedInstanceState == null){
            if(callRole == CallRole.CALLEE){
                LCallManager.stopIncomingCallService(callIntent.roomId, tag = "accept: has in call activity")
            }
            logIntent(callIntent)
            processIntent(callIntent)
        }else {
            L.i { "[Call] LCallActivity: Activity likely rotated, not processing intent" }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        registerCallActivityReceiver()
        callPermission.launchMultiplePermission(PermissionUtil.callPermissions)

        initializePictureInPictureParams()

        initializeFaceFittingScreen()
    }

    private fun getCallIntent(): CallIntent {
        return CallIntent(intent)
    }

    private fun processIntent(callIntent: CallIntent) {
        if(callIntent.action == CallIntent.Action.START_CALL || callIntent.action == CallIntent.Action.JOIN_CALL) {
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
            val isUserSharingScreen by viewModel.isParticipantSharedScreen.collectAsState()
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

                                is DisconnectException -> {
                                    L.e { "[call] LCallActivity, viewModel.error DisconnectException: ${it.message}" }
                                    lifecycleScope.launch {
                                        delay(2000L)
                                        endCallAndClearResources()
                                    }
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

                                is UnknownHostException -> {
                                    L.e { "[call] LCallActivity, viewModel.error UnknownHostException: ${it.message}" }
                                    showStyledPopTip(getString(R.string.call_connect_exception_error), onDismiss = { viewModel.dismissError() })
                                }

                                else -> {
                                    L.e { "[call] LCallActivity, viewModel.error OtherException: ${it.message}" }
                                    showStyledPopTip(getString(R.string.call_other_exception_error), onDismiss = { viewModel.dismissError() })
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.isParticipantSharedScreen.collect {
                        L.i { "[call] LCallActivity, viewModel.isParticipantSharedScreen: $it" }
                        LCallManager.setCallScreenSharing(it)
                    }
                }

                launch {
                    viewModel.callStatus.collect {
                        L.i {"[Call] LCallActivity Show CallingEnd Reminder"}
                        if(it == CallStatus.CONNECTED){
                            currentRoomId?.let {
                                callToChatController.cancelNotificationById(currentRoomId.hashCode())
                            }
                        }
                    }
                }

                launch {
                    viewModel.isNoSpeakSoloTimeout.collect {
                        if(it){
                            if(!isShowCallingEndReminder ){
                                L.i {"[Call] LCallActivity Show CallingEnd Reminder"}
                                isShowCallingEndReminder = true
                                callConfig.let { config ->
                                    val waitingSeconds = config.autoLeave.runAfterReminderTimeout
                                    val secondsToLeaveMeeting = waitingSeconds / 1000
                                    showCallingEndReminder(secondsToLeaveMeeting)
                                }
                            }
                        }else{
                            if(isShowCallingEndReminder){
                                L.i {"[Call] LCallActivity dismiss CallingEnd Reminder"}
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

    private var callEndReminderDialog: MessageDialog? = null

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
            val title = if(viewModel.room.remoteParticipants.isEmpty()){
                getString(R.string.call_single_person_timeout_reminder)
            }else{
                getString(R.string.call_all_mute_timeout_reminder)
            }

            callEndReminderDialog =
                MessageDialog.build().setTitle(title)
                    .setMessage("${remainingSecondsToLeaveMeeting}s left")
                    .setCancelable(false)
                    .setOkButton(getString(R.string.call_reminder_button_continue)) { dialog, _ ->
                        viewModel.resetNoBodySpeakCheck()
                        if(viewModel.callTypeStateFlow.value == CallType.ONE_ON_ONE.type){
                            viewModel.room.remoteParticipants.values.firstOrNull()?.let { participant ->
                                viewModel.sendContinueCallRtmMessage(participant)
                            }
                        }
                        dismissCallingEndReminder()
                        true
                    }
                    .setCancelButton(getString(R.string.call_reminder_button_exit)) { dialog, _ ->
                        currentRoomId?.let { roomId ->
                            val callExitParams = CallExitParams(roomId, callIntent.callerId, callRole, viewModel.callTypeStateFlow.value, conversationId)
                            handleExitClick(callExitParams)
                        }
                        true
                    }.setDialogLifecycleCallback(object :
                        DialogLifecycleCallback<MessageDialog>() {
                        override fun onShow(dialog: MessageDialog?) {
                            super.onShow(dialog)
                            dialog?.apply {
                                cancelTextInfo?.fontColor =
                                    ContextCompat.getColor(
                                        this@LCallActivity,
                                        com.difft.android.base.R.color.t_error_night
                                    )
                            }
                        }

                        override fun onDismiss(dialog: MessageDialog?) {
                            super.onDismiss(dialog)
                            if (countdownDispose?.isDisposed == false) {
                                countdownDispose?.dispose()
                            }
                        }
                    })

            if (viewModel.isInPipMode()) {
                val intent = CallIntent.Builder(this, LCallActivity::class.java)
                    .withAction(CallIntent.Action.BACK_TO_CALL)
                    .withIntentFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).build()

                startActivity(intent)
                handler.postDelayed({
                    remainingSecondsToLeaveMeeting = secondsToLeaveMeeting
                    callEndReminderDialog?.setMessage("${remainingSecondsToLeaveMeeting}s left")
                    callEndReminderDialog?.show()
                }, 2000)
            } else {
                callEndReminderDialog?.show()
            }
        } else {
            remainingSecondsToLeaveMeeting -= 1
            callEndReminderDialog?.setMessage("${remainingSecondsToLeaveMeeting}s left")
            if (remainingSecondsToLeaveMeeting <= 0) {
                endCallAndClearResources()
            }
        }

        countdownDispose = Observable.interval(1, TimeUnit.SECONDS, Schedulers.io())
            .take(secondsToLeaveMeeting + 1) // 发射 countDownSeconds + 1 次，因为区间是从 0 开始的
            .map { secondsToLeaveMeeting - it } // 将发射的数字转换为剩余的秒数
            .observeOn(AndroidSchedulers.mainThread()) // 切换到主线程来更新 UI 或执行其他操作
            .subscribe({ remainingSeconds ->
                callEndReminderDialog?.setMessage("${remainingSeconds}s left")
                if (remainingSeconds <= 0) {
                    endCallAndClearResources()
                }
            }, { throwable ->
                // 处理错误
                L.e {"[Call] LCallActivity showCallingEndReminder countdownDispose error:"+throwable.message}
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
        var parentSize by remember { mutableStateOf(Size(0f, 0f))}
        val interactionSource = remember { MutableInteractionSource() }
        val showControlBarEnabled by viewModel.showControlBarEnabled.collectAsState(true)
        val currentCallType by viewModel.callTypeStateFlow.collectAsState()

        fun handleClickScreen() {
            viewModel.showControlBarEnabled.value = !showControlBarEnabled
        }

        LaunchedEffect(currentCallType) {
            callType = currentCallType
        }

        AppTheme(darkTheme = true) {
            ConstraintLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorResource(id = com.difft.android.base.R.color.gray_1000))
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
                                    }
                                    .onGloballyPositioned { coordinates ->
                                        parentSize = Size(
                                            coordinates.size.width.toFloat(),
                                            coordinates.size.height.toFloat()
                                        )
                                    }
                            ){
                                // 1v1 Call UI布局逻辑
                                SingleParticipantCallPage(viewModel, room, muteOtherEnabled, autoHideTimeout, callConfig, conversationId, callIntent.callerId, callRole, handleInviteUsersClick = { handleInviteUsersClick()})
                                // 顶部和底部Control View
                                RenderTopAndBottomOverlays(isOneVOneCall = true, isUserSharingScreen, audioSwitchHandler)
                                // 底部raise hand view
                                ShowHandsUpBottomView(viewModel, onDismiss = { viewModel.setShowHandsUpBottomViewEnabled(false) })

                                ShowItemsBottomView(viewModel, isOneVOneCall = true, onDismiss = { viewModel.setShowToolBarBottomViewEnable(false) }, deNoiseCallBack = { enable -> audioProcessor.setEnabled(enable) }, handleInviteUsersClick = { handleInviteUsersClick() })
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
                                }
                            ) {
                                // 多人 Call UI布局逻辑
                                MultiParticipantCallPage(viewModel, room, muteOtherEnabled, autoHideTimeout, callConfig, handleInviteUsersClick = { handleInviteUsersClick() })
                                // 顶部和底部Control View
                                RenderTopAndBottomOverlays(isOneVOneCall = false, isUserSharingScreen, audioSwitchHandler)
                                // 底部raise hand view
                                ShowHandsUpBottomView(viewModel, onDismiss = { viewModel.setShowHandsUpBottomViewEnabled(false) })

                                ShowItemsBottomView(viewModel, isOneVOneCall = false, onDismiss = { viewModel.setShowToolBarBottomViewEnable(false) }, deNoiseCallBack = { enable -> audioProcessor.setEnabled(enable)}, handleInviteUsersClick = { handleInviteUsersClick() })

                                ShowBottomCallEndView(
                                    viewModel,
                                    onDismiss = {
                                        viewModel.setShowBottomCallEndViewEnable(false)
                                        viewModel.setShowControlBarEnabled(true)
                                    },
                                    onClickItem = { action ->
                                        handleBottomCallEndAction(action)
                                    }
                                )
                            }
                        }
                    }
                }

            }
        }
    }


    companion object {
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

    }

    private val callPermission = registerPermission {
        onMeetingPermissionForCallResult(it)
    }

    private fun onMeetingPermissionForCallResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.i { "onMeetingPermissionForCallResult: Denied" }
                showErrorAndFinish(getString(R.string.no_permission_camera_and_voice_tip))
            }

            PermissionUtil.PermissionState.Granted -> {
                L.i { "onMeetingPermissionForCallResult: Granted" }
                // Setup compose view.
                initView()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.i { "onMeetingPermissionForCallResult: PermanentlyDenied" }
                showErrorAndFinish(getString(R.string.no_permission_camera_and_voice_tip))
            }
        }
    }

    private fun showErrorAndFinish(tips: String) {
        TipDialog.show(tips, WaitDialog.TYPE.WARNING, 4000).dialogLifecycleCallback = object : DialogLifecycleCallback<WaitDialog?>() {
            override fun onDismiss(dialog: WaitDialog?) {
                endCallAndClearResources()
            }
        }
    }


    @SuppressLint("MissingSuperCall")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        L.i { "[Call] LCallActivity onUserLeaveHint" }
        enterPipModeIfPossible(tag = "onUserLeaveHint")
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        L.i { "[Call] LCallActivity onBackPressed" }
        if(!enterPipModeIfPossible(tag = "onBackPressed")){
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        L.i { "[Call] LCallActivity onDestroy start." }
        countdownDispose?.takeIf { !it.isDisposed }?.dispose()
        callEndReminderDialog?.takeIf { it.isShow }?.apply {
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

        audioProcessor.release()

        proximitySensorListener = null

        viewModel.getRoomId()?.let { roomId ->
            LCallManager.updateCallingState(roomId, false)
        }
        L.i { "[Call] LCallActivity onDestroy end." }
    }

    private fun endCallAndClearResources(){
        inCallEnding = true
        viewModel.doExitClear()
        finishAndRemoveTask()
    }

    override fun getResources(): Resources {
        val res = super.getResources()
        // 适配字体缩放问题，字体缩放会导致布局错乱，此处重置为默认值1.0f
        if(res.configuration.fontScale != DEFAULT_FONT_SCALE){
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
            viewModel.setPipModeEnabled(isInPictureInPictureMode)
        }

        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            if(!isInPictureInPictureMode) {

                // 20241003@w ------------------------------------------------->
                // Purpose: Don't terminate the call when the user clicks the PIP "Close" button and the screen is locked
                // Link to issue: https://github.com/difftim/difft-android/issues/1332
                if(LCallManager.isScreenLocked(this)) {
                    L.i { "[Call] LCallActivity onPictureInPictureModeChanged - Lifecycle.State.CREATED - Screen is locked" }
                } else {
                    //when user click on Close button of PIP this will trigger, do what you want here
//                    hangUpTheCall(tag = "onPictureInPictureModeChanged - Lifecycle.State.CREATED")

                    currentRoomId?.let { roomId ->
                        val callExitParams = CallExitParams(roomId, callIntent.callerId, callRole, viewModel.callTypeStateFlow.value, conversationId)
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
                    if(roomId != null && roomId == currentRoomId){
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
        if(actionType == CallActionType.DECLINE.type){
            currentRoomId?.let { roomId ->
                val callExitParams = CallExitParams(roomId, callIntent.callerId, callRole, viewModel.callTypeStateFlow.value, conversationId)
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
        PopTip.build()
            .setBackgroundColor(ContextCompat.getColor(this, com.difft.android.base.R.color.bg2_night))
            .setMessage(message)
            .setMessageTextInfo(TextInfo().apply {
                fontColor = ContextCompat.getColor(this@LCallActivity, com.difft.android.base.R.color.t_primary_night)
            })
            .show()
            .dialogLifecycleCallback = object : DialogLifecycleCallback<PopTip?>() {
            override fun onDismiss(dialog: PopTip?) {
                onDismiss()
            }
        }
    }


    private fun shouldStopCallerRingTone() = callRole == CallRole.CALLER && viewModel.getCallDuration() == 0L


    @Composable
    fun RenderTopAndBottomOverlays(isOneVOneCall: Boolean, isUserSharingScreen: Boolean, audioSwitchHandler: AudioSwitchHandler?){
        val isOverlayVisible by viewModel.showControlBarEnabled.collectAsState(true)
        val isInPipMode by viewModel.isInPipMode.collectAsState(false)

        // 顶部悬浮控件布局逻辑
        MainPageWithTopStatusView(viewModel, isInPipMode, isOneVOneCall, isOverlayVisible, isUserSharingScreen, callConfig, callIntent, windowZoomOutAction = {
            handleWindowZoomOutClick()
        })

        if(!isInPipMode){
            // 底部悬浮控件布局逻辑
            MainPageWithBottomControlView(viewModel, isOneVOneCall, isOverlayVisible, isUserSharingScreen, audioSwitchHandler, { callType, callEndType ->
                viewModel.getRoomId()?.let { roomId ->
                    val callExitParams = CallExitParams(
                        roomId,
                        callIntent.callerId,
                        callRole,
                        callType,
                        conversationId
                    )
                    handleExitClick(callExitParams, callEndType)
                }
            })
        }
    }

    private fun sendCancelCallMessage(onComplete: () -> Unit){
        L.d { "[Call] LCallActivity sendCancelCallMessage" }
        currentRoomId?.let { roomId ->
            callToChatController.cancelCall(callIntent.callerId, callRole, viewModel.callTypeStateFlow.value, roomId, conversationId){
                onComplete()
            }
        }
    }

    private fun handleExitClick(
        params: CallExitParams,
        callEndType: LCallManager.CallEndType? = LCallManager.CallEndType.LEAVE,
        ){
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

        // 根据通话状态和角色发送不同的消息或执行操作
        viewModel.callStatus.value.let { status ->
            if(status == CallStatus.CONNECTED || status == CallStatus.RECONNECTED){
                // 通话中：发送挂断消息并结束通话
                LCallManager.removeCallData(params.roomId)
                L.i { "[Call] LCallActivity send hangUpCall CallMessage roomId:${params.roomId}" }
                viewModel.sendHangUpRtmMessage()
                params.roomId?.let { roomId ->
                    callToChatController.hangUpCall(params.callerId, callRole, callType, roomId, conversationId, viewModel.getCurrentCallUidList(), onComplete = { endCallAndClearResources() } )
                }
            }else{
                // 通话未开始：根据角色发送取消消息或直接结束通话
                when (callRole) {
                    CallRole.CALLER -> {
                        // 主叫：发送取消消息
                        sendCancelCallMessage {
                            // 取消回调（结束后清理资源）
                            endCallAndClearResources()
                        }
                    }
                    else -> {
                        // 被叫或其他角色：直接结束通话
                        endCallAndClearResources()
                    }
                }
            }
        }
    }

    private fun handleInviteUsersClick(){
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

            if(viewModel.callTypeStateFlow.value == CallType.ONE_ON_ONE.type || viewModel.callTypeStateFlow.value == CallType.INSTANT.type){
                val mySelfName = withContext(Dispatchers.Default){
                    LCallManager.getDisplayNameById(mySelfId) ?: mySelfId
                }
                val roomName = "${mySelfName}${getString(R.string.call_instant_call_title)}"
                currentRoomId?.let { roomId ->
                    callToChatController.inviteUsersToTheCall(this@LCallActivity, roomId, roomName, viewModel.getE2eeKey(), CallType.INSTANT.type, conversationId, ArrayList(excludedIds))
                }
            }else{
                currentRoomId?.let { roomId ->
                    callToChatController.inviteUsersToTheCall(this@LCallActivity, roomId, callIntent.roomName, viewModel.getE2eeKey(), CallType.GROUP.type, conversationId, ArrayList(excludedIds))
                }
            }
        }
    }

    private fun handleWindowZoomOutClick(){
        L.d { "[Call] LCallActivity handleWindowZoomOutClick" }
        // 20230417@w --------------------------------------------------------------------->
        // Purpose: Show dialog to notify user that PIP is not supported on this device
        // link to issue: https://jira.toolsfdg.net/projects/WEATOOL/issues/WEATOOL-44
        if (isSystemPipEnabledAndAvailable()) {
            enterPipModeIfPossible(tag = "windowZoomOut")
        } else {
            // show alert dialog
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
        if(!inCallEnding){
            updateOngoingCallNotification(true)
        }

        sensorManager.unregisterListener(proximitySensorListener)
    }

    override fun onResume() {
        super.onResume()
        L.i { "[Call] LCallActivity onResume" }

        proximitySensor?.let {
            sensorManager.registerListener(
                proximitySensorListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        isInForeground = true
        updateOngoingCallNotification(false)
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
                            pipBuilderParams.setAutoEnterEnabled(true)
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
        }else{
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
        when(action) {
            BottomCallEndAction.END_CALL -> {
                L.i { "[call] LCallActivity onClick End" }
                handleExitClick(
                    createCallExitParams(),
                    LCallManager.CallEndType.END
                )
                viewModel.setShowBottomCallEndViewEnable(false)
            }
            BottomCallEndAction.LEAVE_CALL -> {
                L.i { "[call] LCallActivity onClick Leave" }
                handleExitClick(
                    createCallExitParams(),
                    LCallManager.CallEndType.LEAVE
                )
                viewModel.setShowBottomCallEndViewEnable(false)
            }
            else -> {
                viewModel.setShowBottomCallEndViewEnable(false)
                viewModel.setShowControlBarEnabled(true)
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
                            if (viewModel.isParticipantSharedScreen.value) {
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
}
