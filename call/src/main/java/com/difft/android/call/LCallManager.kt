package com.difft.android.call

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import autodispose2.autoDispose
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.utils.globalServices
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.StartCallRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.databinding.LayoutCallWaitDialogBinding
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.util.ThemeUtil
import difft.android.messageserialization.For
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.ui.CallRatingFeedbackView
import com.difft.android.call.util.CallComposeUiUtil
import com.difft.android.network.config.FeatureGrayManager
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.CompletableSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import livekit.LivekitTemptalk
import org.difft.app.database.models.ContactorModel
import util.ScreenLockUtil
import java.util.concurrent.TimeUnit

object LCallManager {

    var isIncomingCalling = false
    private var isCallScreenSharing = false
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var isLooping = false
    private const val DEF_INCOMING_CALL_TIMEOUT = 56L // Seconds
    const val DEF_ONGOING_CALL_TIMEOUT = 60L // Seconds
    const val DEF_LEAVE_CALL_TIMEOUT = 60L // Seconds
    private const val LOADING_ANIM_DARK = "tt_loading_dark.json"
    private const val LOADING_ANIM_LIGHT = "tt_loading_light.json"

    private val callServiceUrls = mutableListOf<String>()

    /**
     * Represents how a call was terminated
     * LEAVE - Individual participant left the call
     * END - Call was terminated for all participants
     */
    enum class CallEndType {
        LEAVE,
        END
    }

    private val autoDisposeCompletable = CompletableSubject.create()

    enum class CallState {
        ONGOING_CALL,
        INCOMING_CALL,
        LEAVE_CALL,
    }

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        var callToChatController: LCallToChatController

        @ChativeHttpClientModule.Chat
        fun httpClient(): ChativeHttpClient

        @ChativeHttpClientModule.Call
        fun callHttpClient(): ChativeHttpClient
    }

    data class ControlMessage(
        val actionType: CallActionType,
        val roomId: String
    )

    private val _controlMessage = MutableStateFlow<ControlMessage?>(null)

    val controlMessage = _controlMessage.asStateFlow()

    fun updateControlMessage(message: ControlMessage?) {
        _controlMessage.value = message
    }

    fun clearControlMessage() {
        _controlMessage.value = null
    }

    private val callService by lazy {
        val callHttpClient = EntryPointAccessors.fromApplication<EntryPoint>(com.difft.android.base.utils.application).callHttpClient()
        callHttpClient.getService(LCallHttpService::class.java)
    }

    private val callToChatController: LCallToChatController by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).callToChatController
    }


    private val _chatHeaderCallVisibility: BehaviorSubject<Boolean> = BehaviorSubject.createDefault(false)

    val chatHeaderCallVisibility: Observable<Boolean> = _chatHeaderCallVisibility

    fun setChatHeaderCallVisibility(visibility: Boolean) {
        _chatHeaderCallVisibility.onNext(visibility)
    }

    private val _callingTime = MutableLiveData<Pair<String, String>?>()
    val callingTime: LiveData<Pair<String, String>?> = _callingTime

    private val contactorCache: MutableMap<String?, ContactorModel?> = mutableMapOf()

    private val _callingList: BehaviorSubject<MutableMap<String, CallData>> = BehaviorSubject.createDefault(mutableMapOf())
    val callingList: Observable<MutableMap<String, CallData>> = _callingList

    private var callFeedbackInfo: FeedbackCallInfo? = null

    fun updateCallingListData(call: MutableMap<String, CallData>) {
        _callingList.onNext(if (call.isNotEmpty()) call else mutableMapOf())
    }

    fun getCallDataByConversationId(conversationId: String): CallData? {
        getCallListData()?.forEach {
            if (it.value.conversation == conversationId && it.value.type != CallType.INSTANT.type) {
                return it.value
            }
        }
        return null
    }

    fun getCallListData(): MutableMap<String, CallData>? {
        return _callingList.value
    }

    fun getCallData(roomId: String?): CallData? {
        getCallListData()?.forEach {
            if (it.value.roomId == roomId) {
                return it.value
            }
        }
        return null
    }

    fun removeCallData(roomId: String?) {
        if (!roomId.isNullOrEmpty()) {
            getCallListData()?.let {
                it.remove(roomId)
                updateCallingListData(it)
            }
        }
    }

    fun addCallData(call: CallData) {
        if (call.roomId.isNotEmpty()) {
            getCallListData()?.let {
                if (!it.containsKey(call.roomId)) {
                    it[call.roomId] = call
                    updateCallingListData(it)
                }
            }
        }
    }

    fun hasCallDataNotifying(): Boolean {
        return getCallListData()?.filterValues { it.notifying == true }.isNullOrEmpty().not()
    }

    private fun setCallNotifyStatus(roomId: String, notifying: Boolean) {
        try {
            getCallListData()?.let { callListData ->
                callListData.map { it.value }.firstOrNull { it.roomId == roomId }?.let {
                    it.notifying = notifying
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAllCallData() {
        _callingList.value?.clear()
        updateCallingListData(mutableMapOf())
    }

    suspend fun updateCallContactorCache(uidList: List<String>) {
        withContext(Dispatchers.IO) {
            uidList.mapNotNull { uid ->
                if (contactorCache.containsKey(uid)) {
                    callToChatController.getContactorById(application, uid)
                } else {
                    null
                }
            }.forEach { contactor ->
                if (contactor.isPresent) {
                    contactorCache[contactor.get().id] = contactor.get()
                }
            }
        }
    }

    suspend fun getDisplayName(id: String?): String? {
        id?.let {
            val userId = it.split(".").firstOrNull() ?: it
            contactorCache[userId]?.let { contactor ->
                L.d { "[Call] DisplayName found in cache for userId: $userId, displayName: ${contactor.getDisplayNameForUI()}" }
                return contactor.getDisplayNameForUI()
            }

            callToChatController.getContactorById(application, userId).let { contactor ->
                if (contactor.isPresent) {
                    contactorCache[userId] = contactor.get()
                    L.d { "[Call] DisplayName retrieved for userId: $userId, displayName: ${contactor.get().getDisplayNameForUI()}" }
                    return contactor.get().getDisplayNameForUI()
                } else {
                    contactorCache[userId] = null
                }
            }

            L.i { "[Call] getDisplayName No displayName found for userId: $userId" }
        }
        return null
    }

    suspend fun getDisplayNameById(id: String?): String? {
        return getDisplayName(id) ?: convertToBase58UserName(id)
    }

    suspend fun getAvatarByUid(context: Context, id: String?): ConstraintLayout? {
        id?.let {
            val userId = it.split(".").firstOrNull() ?: it
            contactorCache[userId]?.let { contactor ->
                return callToChatController.getAvatarByContactor(context, contactor)
            }

            callToChatController.getContactorById(context, userId).let { contactor ->
                if (contactor.isPresent) {
                    contactorCache[userId] = contactor.get()
                    return callToChatController.getAvatarByContactor(context, contactor.get())
                } else {
                    contactorCache[userId] = null
                }
            }
        }
        L.i { "[Call] getAvatarByUid No avatar found for provided id." }
        return null
    }

    fun createAvatarByNameOrUid(context: Context, name: String?, id: String): ConstraintLayout {
        return callToChatController.createAvatarByNameOrUid(context, name, id)
    }

    fun clearContactorCache() {
        contactorCache.clear()
    }


    fun joinCall(context: Context, callData: CallData, onComplete: (Boolean) -> Unit) {

        if(isIncomingCalling) {
            try {
                callToChatController?.getIncomingCallRoomId()?.let { roomId ->
                    stopIncomingCallService(roomId, "stop incoming call")
                }
            } catch (e: Exception) {
                L.e { "[Call] LCallManager joinCall stopIncomingCallService error: ${e.message}" }
            }
        }

        callData.let {
            val id = it.roomId
            val callType = CallType.fromString(it.type.toString()) ?: CallType.ONE_ON_ONE
            val callerId = it.caller.uid
            val conversationId = it.conversation
            val callName = it.callName
            if (id.isNotEmpty() && !callerId.isNullOrEmpty()) {
                callToChatController.joinCall(
                    context = context,
                    roomId = id,
                    roomName = callName,
                    callerId = callerId,
                    callType = callType,
                    conversationId = conversationId,
                    isNeedAppLock = false
                ) { status ->
                    onComplete(status)
                }
            }
        }
    }


    private var application = ApplicationHelper.instance

    fun isScreenLocked(context: Context): Boolean {
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        // 检查设备是否处于锁定状态
        val isLocked = keyguardManager.isKeyguardLocked
        // 检查设备是否处于安全锁定状态，即需要PIN码、图案或密码才能解锁
        val isSecureLocked = keyguardManager.isKeyguardSecure
        L.d { "[Call] LCallManager isScreenLocked: isLocked=$isLocked, isSecureLocked=$isSecureLocked" }
        return isLocked
    }

    fun playHangupRingTone(viewModel: LCallViewModel) {
        viewModel.viewModelScope.launch {
            L.d { "[Call] LCallManager: set ringTone call_hangup" }
            val ringtoneUri = Uri.parse("android.resource://${application.packageName}/${R.raw.new_call_hangup}")
            if (ringtoneUri != null) {
                L.d { "[Call] LCallManager: do playCallHangupTone" }
                ringtone = RingtoneManager.getRingtone(application, ringtoneUri)
            }
            if (ringtone == null) {
                L.d { "[Call] LCallManager: call_hangup ringTone is null" }
            }
            ringtone?.play()
        }
    }


    private fun playRingTone(
        context: Context, callType: String?, roleType: String?, tag: String? = null
    ) {
        L.d { "[Call] LCallManager playRingTone: callType=$callType, roleType=$roleType, tag=$tag" }
        var ringtoneUri: Uri? = null

        GlobalScope.launch {
            if (ringtone == null) {
                when (callType) {

                    CallType.ONE_ON_ONE.type -> {
                        ringtoneUri =
                            if (roleType == CallRole.CALLER.type) {
                                L.d { "[Call] LCallManager: set ringTone call_outgoing_1v1" }
                                Uri.parse("android.resource://${context.packageName}/${R.raw.new_call_outgoing_1v1}")
                            } else {
                                L.d { "[Call] LCallManager: set ringTone new_call_incomming_1v1" }
                                Uri.parse("android.resource://${context.packageName}/${R.raw.new_call_incomming_1v1}")
                            }
                    }

                    CallType.GROUP.type, CallType.INSTANT.type -> {
                        if (roleType == CallRole.CALLEE.type) {
                            L.d { "[Call] LCallManager: set ringTone call_incomming_group" }
                            ringtoneUri =
                                Uri.parse("android.resource://${context.packageName}/${R.raw.new_call_incomming_group}")
                            isLooping = false
                        }
                    }
                }

                if (ringtoneUri != null) {
                    L.d { "[Call] LCallManager: do playRingTone, isLooping=$isLooping" }
                    ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
                }

                if (isLooping) {
                    startLoopingRingtone()
                } else {
                    ringtone?.play()
                }
            }

        }

    }

    private fun startLoopingRingtone() {
        ringtone?.play()
        // 通过 Handler 延迟一定时间后再次调用 play() 来模拟循环播放
        val ringtoneDuration = 3000 // 假设铃声时长为 3 秒
        handler.postDelayed(object : Runnable {
            override fun run() {
                ringtone?.stop()
                ringtone?.play()
                // 如果仍然需要循环播放，继续延迟调用
                if (isLooping) {
                    handler.postDelayed(this, ringtoneDuration.toLong())
                }
            }
        }, ringtoneDuration.toLong())
    }

    fun stopRingTone(tag: String? = null) {
        L.d { "[Call] LCallManager: stopRingTone, tag=$tag" }
        try {
            isLooping = false
            handler.removeCallbacksAndMessages(null) // 停止循環播放
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: IllegalStateException) {
            L.e { "[Call] LCallManager: Ringtone is in an illegal state: ${e.message}" }
        } catch (e: NullPointerException) {
            L.e { "[Call] LCallManager: NullPointerException encountered while stopping ringtone: ${e.message}" }
        } finally {
            ringtone = null // 釋放資源
        }
    }

    fun startRingTone(intent: Intent) {
        val callType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE)
        val roleType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE)
        playRingTone(ApplicationHelper.instance, callType, roleType, tag = "showCallNotification")
    }

    private fun wakeupDevice() {
        val context = application.applicationContext
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            L.d { "[Call] LCallManager wakeupDevice" }
            // if screen is not already on, turn it on (get wake_lock)
            val wl: PowerManager.WakeLock = powerManager.newWakeLock(
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE or
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "id:wakeupscreen"
            )
            wl.acquire()
        }
    }


    private val vibrator: Vibrator by lazy {
        application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun getVibratorService(): Vibrator {
        return vibrator
    }

    @SuppressLint("MissingPermission")
    fun startVibration() {
        try {
            val pattern: LongArray = longArrayOf(0, 1000, 1000)
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, 1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE)
                )
            } else {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()

                vibrator.vibrate(pattern, 1, audioAttributes)
            }
        } catch (e: Exception) {
            L.e { "[call] LCallManger start Vibration fail:" + e.stackTraceToString() }
        }
    }

    fun stopVibration() {
        vibrator.cancel()
    }

    fun bringInCallScreenBack(context: Context) {
        L.d { "[call] LCallManger bringInCallScreenBack" }
        context.startActivity(createBackToCallIntent(context))
    }

    private fun createBackToCallIntent(context: Context): Intent {
        return CallIntent.Builder(context, LCallActivity::class.java)
            .withAction(CallIntent.Action.BACK_TO_CALL)
            .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .build()
    }

    fun getUidByIdentity(identity: String?): String? {
        var userId = identity
        if (!identity.isNullOrEmpty() && identity.contains(".")) {
            userId = identity.split(".")[0]
        }
        return userId
    }

    fun stopIncomingCallService(roomId: String, tag: String? = null) {
        L.d { "[call] LCallManager stopIncomingCallService stop Service, tag=$tag" }
        L.i { "[call] LCallManager cancel incoming call timeout detection" }
        cancelCallWithTimeout(roomId)
        callToChatController.cancelNotificationById(roomId.hashCode())
        stopRingTone(tag = tag)
        stopVibration()
        setCallNotifyStatus(roomId, false)
        application.sendBroadcast(
            Intent(LCallConstants.CALL_OPERATION_INVITED_DESTROY)
                .setPackage(application.packageName)
                .putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, roomId)
        )
    }

    fun startIncomingCallService(intent: Intent) {
        val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_ROOM_ID) ?: return
        if (roomId.isEmpty()) return

        val callType: CallType =
            intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE)?.let {
                CallType.fromString(it)
            } ?: CallType.ONE_ON_ONE

        if (!LCallActivity.isInCalling() && !hasCallDataNotifying()) {
            startRingTone(intent)
            startVibration()
            setCallNotifyStatus(roomId, true)
        }

        val callerId: String = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALLER_ID) ?: ""
        val conversationId: String? = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CONVERSATION_ID)
        val callName: String = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_NAME) ?: ""
        if (!callToChatController.isIncomingCallActivityShowing() && !LCallActivity.isInCalling() && callToChatController.isAppForegrounded()) {
            val intentActivity = CallIntent.Builder(application, globalServices.activityProvider.getActivityClass(ActivityType.L_INCOMING_CALL))
                .withAction(CallIntent.Action.INCOMING_CALL)
                .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .withRoomId(roomId)
                .withRoomName(callName)
                .withCallType(callType.type)
                .withCallerId(callerId)
                .withConversationId(conversationId)
                .withCallRole(CallRole.CALLEE.type)
                .withNeedAppLock(false)
                .build()
            application.startActivity(intentActivity)
        } else {
            L.i { "[call] LCallManager startIncomingCallService showCallNotification roomId:$roomId" }
            callToChatController.showCallNotification(roomId, callName, callerId, conversationId, callType, true)
        }

        L.i { "[call] LCallManager enable incoming call timeout detection" }
        checkCallWithTimeout(CallState.INCOMING_CALL, DEF_INCOMING_CALL_TIMEOUT, roomId, callBack = { stopNotificationAndService(roomId) })
        wakeupDevice()
        ScreenLockUtil.temporarilyDisabled = true
    }

    private fun stopNotificationAndService(roomId: String) {
        stopIncomingCallService(roomId)
    }

    fun cancelCallWithTimeout(roomId: String) {
        checkCallTimeoutTaskMap[roomId]?.dispose()
        checkCallTimeoutTaskMap.remove(roomId)
    }

    private val checkCallTimeoutTaskMap = mutableMapOf<String, CompositeDisposable>()

    fun checkCallWithTimeout(callState: CallState, timeoutSecond: Long, roomId: String, callBack: (Boolean) -> Unit) {
        // 清除之前的任务（如果存在）
        checkCallTimeoutTaskMap.remove(roomId)?.dispose()

        val compositeDisposable = CompositeDisposable()
        checkCallTimeoutTaskMap[roomId] = compositeDisposable

        val timeoutObservable = Observable.timer(timeoutSecond, TimeUnit.SECONDS)
            .map { Unit }
        val timeoutDisposable = timeoutObservable
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    L.i { "[Call] LCallManager Timeout stop IncomingCall service roomId:$roomId" }
                    callBack(true)
                },
                { it.printStackTrace() }
            )
        when (callState) {
            CallState.INCOMING_CALL -> {
                val checkCallObservable = Observable.interval(5, TimeUnit.SECONDS)
                    .map { Unit }
                    .takeUntil(timeoutObservable)

                val checkCallDisposable = checkCallObservable
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                        {
                            callService.checkCall(SecureSharedPrefsUtil.getToken(), roomId)
                                .subscribeOn(Schedulers.io())
                                .autoDispose(autoDisposeCompletable)
                                .subscribe(
                                    {
                                        if (it.status == 0 && (it.data == null || it.data?.userStopped == true || it.data?.anotherDeviceJoined == true)) {
                                            L.e { "[Call] LCallManager check call result stopIncomingCallService" }
                                            callBack(true)
                                        }
                                    },
                                    { error ->
                                        L.e { "[Call] LCallManager check call error: ${error.message}" }
                                        // response is null
                                        callBack(false)
                                    }
                                )
                        },
                        {
                            it.printStackTrace()
                        }
                    )

                compositeDisposable.add(timeoutDisposable)
                compositeDisposable.add(checkCallDisposable)
            }

            CallState.ONGOING_CALL, CallState.LEAVE_CALL -> {
                compositeDisposable.add(timeoutDisposable)
            }
        }
    }


    fun restoreCallActivityIfInCalling() {
        L.i { "[Call] LCallManager: Try to restoreCallActivityIfInMeeting - inCalling=${LCallActivity.isInCalling()}, isInForeground=${LCallActivity.isInForeground}" }
        if (LCallActivity.isInCalling() && !LCallActivity.isInForeground && !isScreenLocked(
                com.difft.android.base.utils.application
            )
        ) {
            L.i { "[Call] LCallManager: OK> restoreCallActivityIfInMeeting - inCalling=${LCallActivity.isInCalling()}, isInForeground=${LCallActivity.isInForeground}" }
            application.startActivity(createBackToCallIntent(application))
        }
    }

    fun restoreIncomingCallActivityIfIncoming() {
        callToChatController.restoreIncomingCallActivityIfIncoming()
    }

    fun sendOrLocalCallTextMessage(callActionType: CallActionType, textContent: String, sourceDevice: Int, timestamp: Long, systemShowTime: Long, fromWho: For, forWhat: For, callType: CallType, createCallMsg: Boolean, inviteeLIst: List<String> = emptyList()) {
        callToChatController.sendOrCreateCallTextMessage(callActionType, textContent, sourceDevice, timestamp, systemShowTime, fromWho, forWhat, callType, createCallMsg, inviteeLIst)
    }

    fun removePendingMessage(source: String, timestamp: String) {
        val chatHttpClient = EntryPointAccessors.fromApplication<EntryPoint>(com.difft.android.base.utils.application).httpClient()
        chatHttpClient.httpService.removePendingMessage(SecureSharedPrefsUtil.getBasicAuth(), source, timestamp)
            .compose(RxUtil.getSingleSchedulerComposer())
            .autoDispose(autoDisposeCompletable)
            .subscribe({
            }, { it.printStackTrace() })
    }

    fun convertToBase58UserName(identity: String?): String? {
        val userId = identity?.split(".")?.firstOrNull() ?: return null
        if (!ValidatorUtil.isUid(userId)) {
            L.e { "[Call] LCallManager convertToBase58UserName error identity:$identity" }
            return null
        }
        return userId.formatBase58Id()
    }

    suspend fun getParticipantDisplayInfo(context: Context, uid: String): CallUserDisplayInfo {
        val name = getDisplayNameById(uid)
        val avatar = getAvatarByUid(context, uid) ?: createAvatarByNameOrUid(context, name, uid)
        return CallUserDisplayInfo(uid, name, avatar)
    }

    fun updateCallingTime(roomId: String, callingTime: String) {
        _callingTime.value = Pair(roomId, callingTime)
    }

    fun resetCallingTime() {
        _callingTime.value = null
    }

    fun setCallScreenSharing(isSharing: Boolean) {
        isCallScreenSharing = isSharing
    }

    fun isCallScreenSharing(): Boolean {
        return isCallScreenSharing
    }


    fun getContactsUpdateListener(): Observable<List<String>> {
        return callToChatController.getContactsUpdateListener()
    }

    fun isPersonalMobileDevice(identity: String?): Boolean {
        if (identity == null) return false
        return identity.matches(Regex("""^\+[0-9]+\.1${'$'}"""))
    }

    fun showWaitDialog(context: Context) {
        ComposeDialogManager.showWait(
            context = context,
            message = "",
            cancelable = false,
            layoutId = R.layout.layout_call_wait_dialog
        ) { view ->
            val binding = LayoutCallWaitDialogBinding.bind(view)
            val loadingAnimFile = if (ThemeUtil.isSystemInDarkTheme(context)) {
                LOADING_ANIM_DARK
            } else {
                LOADING_ANIM_LIGHT
            }
            binding.callLoadingProcess.setAnimation(loadingAnimFile)
            binding.callLoadingProcess.playAnimation()
        }
    }

    fun dismissWaitDialog() {
        ComposeDialogManager.dismissWait()
    }


    /**
     * Updates the calling state for a specific room
     * @param roomId The ID of the room to update
     * @param isInCalling Whether the room is currently in an active call
     */
    fun updateCallingState(roomId: String, isInCalling: Boolean) {
        getCallListData()?.let { callingData ->
            callingData[roomId]?.let { roomData ->
                roomData.isInCalling = isInCalling
                updateCallingListData(callingData)
            }
        }
    }

    fun createStartCallParams(params: StartCallRequestBody): ByteArray {
        val params = LivekitTemptalk.TTStartCall.newBuilder().apply {
            this.type = params.type
            this.timestamp = params.timestamp

            this.version = params.version

            params.conversation?.let { conversation ->
                this.conversationId = conversation
            }

            params.publicKey?.let { publicKey ->
                this.publicKey = publicKey
            }
            params.roomId?.let { roomId ->
                this.roomId = roomId
            }

            params.notification?.let { notification ->
                this.notification =  LivekitTemptalk.TTNotification.newBuilder().apply {
                    type = notification.type
                    notification.args.let { notificationArgs ->
                        args = LivekitTemptalk.TTNotification.TTArgs.newBuilder().apply {
                            collapseId = notificationArgs.collapseId
                        }.build()
                    }
                }.build()
            }

            params.encInfos?.let { encInfos ->
                val encInfos: List<LivekitTemptalk.TTEncInfo> = encInfos.map { data ->
                    LivekitTemptalk.TTEncInfo.newBuilder().apply {
                        emk = data.emk
                        uid = data.uid
                    }.build()
                }
                addAllEncInfos(encInfos)
            }

            params.cipherMessages?.let { cipherMessages ->
                val cipherMessages: List<LivekitTemptalk.TTCipherMessages> = cipherMessages.map { data ->
                    LivekitTemptalk.TTCipherMessages.newBuilder().apply {
                        uid = data.uid
                        content = data.content
                        registrationId = data.registrationId
                    }.build()
                }
                addAllCipherMessages(cipherMessages)
            }

        }.build()

        return params.toByteArray()
    }

    suspend fun fetchCallServiceUrlAndCache(): List<String> {
        try {
            val response = withContext(Dispatchers.IO) {
                callService.getServiceUrl(SecureSharedPrefsUtil.getToken()).blockingGet()
            }
            return if(response.status == 0){
                val serviceUrls = response.data?.serviceUrls
                if(!serviceUrls.isNullOrEmpty()) {
                    callServiceUrls.clear()
                    callServiceUrls.addAll(serviceUrls)
                    serviceUrls
                }else {
                    emptyList()
                }
            }else{
                emptyList()
            }
        }catch (e: Exception) {
            L.e { "[Call] LCallManager fetchCallServiceUrl failed:${e.message}" }
            return emptyList()
        }
    }

    fun getCallServiceUrl(): List<String> {
        return callServiceUrls.toList()
    }

    fun submitCallFeedback(params: CallFeedbackRequestBody) {
        val token = SecureSharedPrefsUtil.getToken()
        if (token.isNullOrEmpty()) {
            L.e { "[Call] submitCallFeedback failed: missing authentication token" }
            return
        }
        callService
            .callFeedback(token,params)
            .compose(RxUtil.getSingleSchedulerComposer())
            .autoDispose(autoDisposeCompletable)
            .subscribe(
                { it->
                    if(it.status == 0) {
                        L.i { "[Call] submitCallFeedback, request success" }
                    }else{
                        L.e { "[Call] submitCallFeedback, request fail:${it.reason}" }
                    }
                },
                {
                    L.e { "[Call] submitCallFeedback, request fail, error:${it.message}" }
                }
            )
    }

    fun showCallFeedbackView(activity: Activity, callInfo: FeedbackCallInfo) {
        val composeView = androidx.compose.ui.platform.ComposeView(activity)
        composeView.setContent {
            CallRatingFeedbackView(
                callInfo = callInfo,
                onDisplay = {
                    clearCallFeedbackInfo()
                },
                onDismiss = {
                    CallComposeUiUtil.removeComposeViewFromActivity(activity, composeView)
                },
                onSubmit = { data ->
                    submitCallFeedback(data)
                }
            )
        }
        try {
            CallComposeUiUtil.addComposeViewToActivity(activity, composeView)
        }catch (e: Exception){
            L.e { "[Call] Feedback addComposeViewToActivity error: ${e.message}" }
        }
    }

    fun getMyIdentity(): String {
        return "${globalServices.myId}.$DEFAULT_DEVICE_ID"
    }

    fun setCallFeedbackInfo(info: FeedbackCallInfo?) {
        callFeedbackInfo = info
    }

    fun clearCallFeedbackInfo() {
        callFeedbackInfo = null
    }

    fun getCallFeedbackInfo(): FeedbackCallInfo? {
        return callFeedbackInfo
    }

    suspend fun checkQuicFeatureGrayStatus() {
        try {
            val type = if (FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK)) CONNECTION_TYPE.HTTP3_QUIC else CONNECTION_TYPE.WEB_SOCKET
            L.i { "[call] LCallManager checkQuicFeatureGrayStatus: $type" }
            LCallEngine.setSelectedConnectMode(type)
        } catch (e: Exception) {
            L.e { "[Call] LCallManager checkQuicFeatureGrayStatus error: ${e.message}" }
        }
    }
}