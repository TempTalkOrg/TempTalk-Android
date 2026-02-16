package com.difft.android.call

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import com.difft.android.base.BaseActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.utils.RxUtil
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.databinding.CallActivityIncomingCallBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.manager.CallVibrationManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.util.StringUtil
import dagger.Lazy

@AndroidEntryPoint
class LIncomingCallActivity : BaseActivity() {

    @Inject
    lateinit var callToChatController: LCallToChatController

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var inComingCallStateManager: InComingCallStateManager

    @Inject
    lateinit var callDataManagerLazy: Lazy<CallDataManager>

    @Inject
    lateinit var callVibrationManager: CallVibrationManager

    @Inject
    lateinit var callRingtoneManager: CallRingtoneManager

    val binding: CallActivityIncomingCallBinding by viewbind()

    private lateinit var pipBuilderParams: PictureInPictureParams.Builder

    private lateinit var callIntent: CallIntent

    private val callType: CallType by lazy {
        CallType.fromString(callIntent.callType) ?: CallType.ONE_ON_ONE
    }

    private lateinit var backPressedCallback: OnBackPressedCallback

    private lateinit var appUnlockListener: (Boolean) -> Unit

    private val callbackId = "LIncomingCallActivity_${System.identityHashCode(this)}"

    @Inject
    lateinit var userManager: UserManager

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    private var isAcceptingCall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.d { "[Call] LIncomingCallActivity onCreate: onCreate" }

        // 仅在非宽屏设备上强制竖屏，宽屏设备允许自由旋转以撑满全屏
        if (!WindowSizeClassUtil.shouldUseDualPaneLayout(this)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        initializeActivityState()

        registerIncomingCallReceiver()

        handleCallNotificationAction()

        initUI()

        initializePictureInPictureParams()

        setupCallControlMessageListener()

        registerOnBackPressedHandler()

        registerAppUnlockListener()

    }

    private fun initializeActivityState() {
        inComingCallStateManager.setIsActivityShowing(true)
        callIntent = getCallIntent()

        if (isAppLockEnabled()) {
            inComingCallStateManager.setNeedAppLock(callIntent.needAppLock)
        } else {
            inComingCallStateManager.setNeedAppLock(false)
        }

        inComingCallStateManager.setCurrentRoomId(callIntent.roomId)
    }

    private fun showNewCallToastAndHangUp() {
        Toast.makeText(this, R.string.call_newcall_tip, Toast.LENGTH_LONG).show()
        hangUpTheCall("reject: local reject the new call")
    }

    private fun setupCallControlMessageListener() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                onGoingCallStateManager.controlMessage.collect { message ->
                    handleCallControlMessage(message)
                }
            }
        }
    }

    private fun handleCallNotificationAction() {
        if (callIntent.action == CallIntent.Action.ACCEPT_CALL) {
            L.i { "[Call] LIncomingCallActivity handleCallNotificationAction ACCEPT_CALL roomId:${callIntent.roomId}" }
            if (onGoingCallStateManager.isInCalling()) {
                LCallManager.bringCallScreenToFront(this)
                showNewCallToastAndHangUp()
            } else {
                callToChatController.joinCall(applicationContext, callIntent.roomId, callIntent.roomName, callIntent.callerId, callType, callIntent.conversationId, inComingCallStateManager.isNeedAppLock()) { status ->
                    handleJoinCallResponse(status)
                }
            }
        }
    }

    private fun logIntent(callIntent: CallIntent) {
        L.d { "[Call] LIncomingCallActivity logIntent:$callIntent" }
    }

    private fun getCallIntent(): CallIntent {
        return CallIntent(intent)
    }


    private fun handleCallControlMessage(controlMessage: OnGoingCallStateManager.ControlMessage?) {
        if (controlMessage == null) return
        when (controlMessage.actionType) {
            CallActionType.REJECT, CallActionType.JOINED, CallActionType.CALLEND, CallActionType.CANCEL -> {
                if (controlMessage.roomId == callIntent.roomId) {
                    L.i { "[Call] handleControlMessage: actionType:${controlMessage.actionType} roomId:${controlMessage.roomId}" }
                    hangUpTheCall("LIncomingCallActivity hangUpTheCall actionType:${controlMessage.actionType}")
                }
            }

            else -> {}
        }
    }


    @SuppressLint("WrongConstant")
    private fun registerIncomingCallReceiver() {
        val filter = IntentFilter()
        filter.addAction(LCallConstants.CALL_NOTIFICATION_OPERATION_ACCEPT_OTHER)
        filter.addAction(LCallConstants.CALL_OPERATION_INVITED_DESTROY)
        ContextCompat.registerReceiver(
            this,
            inComingCallReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val inComingCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_ROOM_ID)
                if (it.`package` == ApplicationHelper.instance.packageName) {
                    when (intent.action) {
                        LCallConstants.CALL_OPERATION_INVITED_DESTROY -> {
                            if (!roomId.isNullOrEmpty() && roomId == callIntent.roomId) {
                                L.i { "[Call] LIncomingCallActivity inComingCallReceiver: action CALL_OPERATION_INVITED_DESTROY" }
                                finishAndRemoveTask()
                            }
                        }
                    }
                }
            }
        }
    }


    private fun initUI() {
        ContactorUtil.getContactWithID(this@LIncomingCallActivity, callIntent.callerId)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this@LIncomingCallActivity)).subscribe({
                if (it.isPresent) {
                    val contactor = it.get()
                    binding.callerName.text = contactor.getDisplayNameForUI()
                    binding.callerAvatar.setAvatar(contactor)
                } else {
                    binding.callerName.text = callIntent.callerId
                    binding.callerAvatar.setAvatar(null, null, ContactorUtil.getFirstLetter(callIntent.callerId), callIntent.callerId)
                }
            }, { error ->
                L.e { "[Call] LIncomingCallActivity initUI: Failed to get contact with id: ${callIntent.callerId}" }
                binding.callerName.text = callIntent.callerId
                binding.callerAvatar.setAvatar(null, null, ContactorUtil.getFirstLetter(callIntent.callerId), callIntent.callerId)
            })

        val roomName = StringUtil.truncateWithEllipsis(callIntent.roomName, 25)

        val tipMessage = if (callType.isGroup()) {
            ResUtils.getString(com.difft.android.chat.R.string.call_invite_of_group) + ":" + roomName
        } else {
            ResUtils.getString(com.difft.android.chat.R.string.call_invite)
        }
        binding.tipMessage.text = tipMessage

        binding.acceptCallBtn.setOnClickListener {
            if (isAcceptingCall) return@setOnClickListener
            isAcceptingCall = true
            setAcceptLoading(true)
            lifecycleScope.launch {
                val canJoin = if (!onGoingCallStateManager.isInCalling()) {
                    true
                } else {
                    L.i { "[Call] LIncomingCallActivity acceptCallBtn click: end current call before join." }
                    endCurrentCallAndWaitForRelease()
                }

                if (!canJoin) {
                    setAcceptLoading(false)
                    isAcceptingCall = false
                    return@launch
                }

                L.i { "[Call] LIncomingCallActivity initUI: acceptCallBtn click: callIntent:$callIntent" }
                callToChatController.joinCall(
                    applicationContext,
                    callIntent.roomId,
                    callIntent.roomName,
                    callIntent.callerId,
                    callType,
                    callIntent.conversationId,
                    inComingCallStateManager.isNeedAppLock()
                ) { status ->
                    handleJoinCallResponse(status)
                }
            }
        }
        binding.rejectCallBtn.setOnClickListener {
            callToChatController.rejectCall(callIntent.callerId, CallRole.CALLEE, callType.type, callIntent.roomId, callIntent.conversationId) {
                if (callType == CallType.ONE_ON_ONE) {
                    callDataManager.removeCallData(callIntent.roomId)
                }
                hangUpTheCall("reject: local reject the call")
            }
        }

        binding.windowZoomOut.setOnClickListener {
            enterPipModeIfPossible(tag = "windowZoomOut")
        }
    }


    override fun onPause() {
        super.onPause()
        L.i { "[Call] LIncomingCallActivity onPause" }
        inComingCallStateManager.setIsInForeground(false)
    }

    override fun onResume() {
        super.onResume()
        L.i { "[Call] LIncomingCallActivity onResume" }
        inComingCallStateManager.setIsInForeground(true)
    }


    override fun onDestroy() {
        super.onDestroy()
        L.d { "[Call] LIncomingCallActivity onDestroy: onDestroy" }
        unregisterReceiver(inComingCallReceiver)
        inComingCallStateManager.reset() // 一次性重置所有状态
        onGoingCallStateManager.clearControlMessage()
        if (::backPressedCallback.isInitialized) {
            backPressedCallback.remove()
        }
        AppLockCallbackManager.removeListener(callbackId)
        checkNotifyStateAndDismiss(callIntent.roomId)
    }


    private var inInPipMode = false

    private fun hangUpTheCall(tag: String) {
        LCallManager.stopIncomingCallService(callIntent.roomId, tag)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inInPipMode = isInPictureInPictureMode
        renderPipMode(isInPictureInPictureMode)
    }

    private fun renderPipMode(isInPictureInPictureMode: Boolean) {
        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            finishAndRemoveTask()
            //when user click on Close button of PIP this will trigger, do what you want here
            hangUpTheCall(tag = "renderPipMode")
        } else if (lifecycle.currentState == Lifecycle.State.STARTED) {
            //when PIP maximize this will trigger
        }

        binding.windowZoomOut.isVisible = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.acceptCallBtn.visibility = View.INVISIBLE
            binding.rejectCallBtn.visibility = View.INVISIBLE
        } else {
            binding.acceptCallBtn.visibility = View.VISIBLE
            binding.rejectCallBtn.visibility = View.VISIBLE
        }
    }


    // Purpose:在用户手动点击音量加减键的时候, 停掉铃声和震动, 按一下就停, 不是用把音量拉到最低 #1440
    // Link to issue: https://github.com/difftim/difft-android/issues/1440
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // 捕捉到音量增加键
                callRingtoneManager.stopRingTone("Volume Up Pressed")
                callVibrationManager.stopVibration()
                true  // 返回 true 表示你已经处理了该事件
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 捕捉到音量减少键
                callRingtoneManager.stopRingTone("Volume Up Pressed")
                callVibrationManager.stopVibration()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        L.i { "[Call] LIncomingCallActivity onUserLeaveHint" }
        enterPipModeIfPossible(tag = "onUserLeaveHint")
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        L.i { "[Call] LIncomingCallActivity onBackPressed" }
        if (!enterPipModeIfPossible(tag = "onBackPressed")) {
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        L.i { "[Call] LIncomingCallActivity onNewIntent" }
        CallIntent(intent).roomId?.let { roomId ->
            if (inComingCallStateManager.isActivityShowing() && roomId.isNotEmpty() && inComingCallStateManager.getCurrentRoomId() != roomId) {
                Toast.makeText(this, R.string.call_newcall_tip, Toast.LENGTH_SHORT).show()
            }
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

    private fun handleJoinCallResponse(status: Boolean) {
        runOnUiThread {
            setAcceptLoading(false)
            isAcceptingCall = false
        }
        if(!status) {
            L.e { "[Call] LIncomingActivity join call failed." }
            runOnUiThread {
                ToastUtil.show(R.string.call_join_failed_tip)
            }
            hangUpTheCall("accept: join the call failed")
        }else {
            hangUpTheCall("accept: join the call")
        }
    }

    private fun setAcceptLoading(isLoading: Boolean) {
        binding.acceptCallBtn.isEnabled = !isLoading
        binding.rejectCallBtn.isEnabled = !isLoading
    }

    private suspend fun endCurrentCallAndWaitForRelease(): Boolean {
        val currentRoomId = onGoingCallStateManager.getCurrentRoomId()
        if (!currentRoomId.isNullOrEmpty()) {
            val intent = Intent(LCallActivity.ACTION_IN_CALLING_CONTROL).apply {
                putExtra(LCallActivity.EXTRA_CONTROL_TYPE, CallActionType.DECLINE.type)
                putExtra(LCallActivity.EXTRA_PARAM_ROOM_ID, currentRoomId)
                setPackage(ApplicationHelper.instance.packageName)
            }
            ApplicationHelper.instance.sendBroadcast(intent)
        }

        return try {
            withTimeoutOrNull(15_000) {
                onGoingCallStateManager.isInCalling.first { !it }
                true
            } ?: run {
                L.w { "[Call] LIncomingCallActivity endCurrentCallAndWaitForRelease timeout." }
                runOnUiThread {
                    ToastUtil.show(R.string.call_join_failed_tip)
                }
                false
            }
        } catch (e: Exception) {
            L.e(e) { "[Call] LIncomingCallActivity endCurrentCallAndWaitForRelease failed." }
            runOnUiThread {
                ToastUtil.show(R.string.call_join_failed_tip)
            }
            false
        }
    }

    private fun registerOnBackPressedHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                L.i { "[Call] LIncomingActivity onBackPressed" }
                enterPipModeIfPossible("back pressed")
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    private fun registerAppUnlockListener() {
        appUnlockListener = {
            if (it) {
                // 用户刚刚通过应用锁解锁
                inComingCallStateManager.setNeedAppLock(false)
            }
        }
        AppLockCallbackManager.addListener(callbackId, appUnlockListener)
    }

    private fun isAppLockEnabled(): Boolean {
        if (!::userManager.isInitialized) return false
        val user = userManager.getUserData() ?: return false
        val hasPattern = !user.pattern.isNullOrEmpty()
        val hasPasscode = !user.passcode.isNullOrEmpty()
        return hasPattern || hasPasscode
    }

    private fun checkNotifyStateAndDismiss(roomId: String) {
        if (callDataManager.getCallNotifyStatus(roomId)) {
            LCallManager.stopIncomingCallService(roomId, "incoming call activity dismissed")
        }
    }
}