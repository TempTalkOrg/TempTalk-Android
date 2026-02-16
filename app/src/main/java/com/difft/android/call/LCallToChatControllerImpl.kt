package com.difft.android.call

import android.content.Context
import android.content.Intent
import androidx.constraintlayout.widget.ConstraintLayout
import autodispose2.autoDispose
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.call.Args
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallEncryptResult
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.ControlMessageRequestBody
import com.difft.android.base.call.InviteCallRequestBody
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.Notification
import com.difft.android.base.call.StartCallRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.CountdownTimer
import com.difft.android.base.user.PromptReminder
import com.difft.android.base.user.defaultBarrageTexts
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.MD5Utils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.data.createStartCallParams
import com.difft.android.call.handler.InviteRequestState
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.common.SendType
import com.difft.android.chat.setting.archive.MessageArchiveManager
import difft.android.messageserialization.For
import difft.android.messageserialization.model.RealSource
import difft.android.messageserialization.model.ScreenShot
import difft.android.messageserialization.model.TextMessage
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.CompletableSubject
import kotlinx.coroutines.rx3.await
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import util.concurrent.TTExecutors
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import util.AppForegroundObserver
import org.thoughtcrime.securesms.util.ForegroundServiceUtil
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.util.UnableToStartException
import com.difft.android.websocket.api.ConversationManager
import com.difft.android.websocket.api.messages.DetailMessageType
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.util.CallMessageCreator
import java.util.ArrayList
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.network.config.WsTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import util.ScreenLockUtil
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.ScreenDeviceUtil
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.chat.contacts.contactsall.sortedByPinyin
import com.difft.android.chat.contacts.data.LENGTH_OF_BOT_ID
import com.difft.android.network.BaseResponse
import com.difft.android.network.config.GlobalConfigsManager
import dagger.Lazy

@Singleton
class LCallToChatControllerImpl @Inject constructor(
    @param:ChativeHttpClientModule.Call
    private val httpClient: ChativeHttpClient,
    private val callMessageCreator: CallMessageCreator,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val pushTextJobFactory: PushTextSendJobFactory,
    private val conversationManager: ConversationManager,
    private val encryptionDataManager: EncryptionDataManager,
    private val wsTokenManager: WsTokenManager,
    private val localMessageCreator: LocalMessageCreator,
    private val dbRoomStore: DBRoomStore,
    private val inComingCallStateManager: InComingCallStateManager,
    private val criticalAlertStateManager: CriticalAlertStateManager,
    private val messageArchiveManager: MessageArchiveManager,
    private val callDataManagerLazy: Lazy<CallDataManager>,
    private val contactorCacheManager: ContactorCacheManager,
    private val globalConfigsManager: GlobalConfigsManager,
    ) : LCallToChatController {

    private val autoDisposeCompletable = CompletableSubject.create()

    private val callService by lazy {
        httpClient.getService(LCallHttpService::class.java)
    }

    private val mySelfId: String by lazy {
        globalServices.myId
    }

    private val callConfig: CallConfig by lazy {
        globalConfigsManager.getNewGlobalConfigs()?.data?.call ?: CallConfig(
            autoLeave = AutoLeave(promptReminder = PromptReminder()),
            chatPresets = defaultBarrageTexts,
            chat = CallChat(),
            countdownTimer = CountdownTimer()
        )
    }

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    // 常量定义
    companion object {
        private const val INVITE_NOTIFICATION_TYPE = 22
        private const val RESPONSE_STATUS_SUCCESS = 0
        private const val RESPONSE_STATUS_INVALID_UIDS = 2
        private const val RESPONSE_STATUS_STALE = 11001
    }

    override fun joinCall(
        context: Context,
        roomId: String,
        roomName: String?,
        callerId: String,
        callType: CallType,
        conversationId: String?,
        isNeedAppLock: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        CallWaitDialogUtil.show(context)

        appScope.launch(Dispatchers.IO) {
            try {
                // 检查并取消critical alert通知
                conversationId?.let { messageNotificationUtil.cancelCriticalAlertNotification(it) }
                // 检查并关闭critical alert页面及铃声
                dismissCriticalAlertIfActive()
                // 清除会话列表 critical alert 高亮状态
                conversationId?.let { dbRoomStore.clearCriticalAlert(it) }

                val body = StartCallRequestBody(
                    callType.type,
                    LCallConstants.CALL_VERSION,
                    System.currentTimeMillis(),
                    conversation = conversationId,
                    roomId = roomId
                )

                val callData = callDataManager.getCallData(roomId)
                val callRole = if (callData?.caller?.uid == mySelfId && callData.caller.did == DEFAULT_DEVICE_ID)
                    CallRole.CALLER
                else
                    CallRole.CALLEE

                val startCallParams = createStartCallParams(body)

                // 刷新 Token（若需要）
                wsTokenManager.refreshTokenIfNeeded()

                LCallManager.checkQuicFeatureGrayStatus()

                val callIntentBuilder = CallIntent.Builder(application, LCallActivity::class.java)
                    .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .withAction(CallIntent.Action.JOIN_CALL)
                    .withRoomName(roomName)
                    .withCallType(callType.type)
                    .withCallRole(callRole.type)
                    .withCallerId(mySelfId)
                    .withConversationId(conversationId)
                    .withStartCallParams(startCallParams)
                    .withAppToken(SecureSharedPrefsUtil.getToken())
                    .withNeedAppLock(isNeedAppLock)
                    .withCallWaitDialogShown(true)

                val speedTestServerUrls = LCallEngine.getAvailableServerUrls()

                // 优先使用缓存的 serverUrl
                if (speedTestServerUrls.isNotEmpty()) {
                    val intent = callIntentBuilder.withCallServerUrls(speedTestServerUrls).build()
                    startCallInternal(context, intent, onComplete)
                    return@launch
                }

                // 无缓存，发起网络请求
                runCatching {
                    callService.getServiceUrl(SecureSharedPrefsUtil.getToken())
                        .compose(RxUtil.getSingleSchedulerComposer())
                        .await()  // ✅ 用扩展函数 await() 将 Rx 转协程挂起，避免嵌套回调
                }.onSuccess { response ->
                    if (response.status == 0 && !response.data?.serviceUrls.isNullOrEmpty()) {
                        val urls = response.data!!.serviceUrls!!
                        val intent = callIntentBuilder.withCallServerUrls(urls).build()
                        startCallInternal(context, intent, onComplete)
                    } else {
                        L.e { "[Call] joinCall getCallServerUrl failed, status:${response.status}" }
                        withContext(Dispatchers.Main) { CallWaitDialogUtil.dismiss() }
                        onComplete(false)
                    }
                }.onFailure { e ->
                    L.e(e) { "[Call] joinCall getCallServerUrl error:" }
                    withContext(Dispatchers.Main) { CallWaitDialogUtil.dismiss() }
                    onComplete(false)
                }
            } catch (e: Exception) {
                L.e(e) { "[Call] joinCall unexpected error:" }
                withContext(Dispatchers.Main) { CallWaitDialogUtil.dismiss() }
                onComplete(false)
            }
        }
    }

    override fun rejectCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?, onComplete: () -> Unit) {
        val callType = CallType.fromString(type) ?: CallType.ONE_ON_ONE

        val forWhat = if (callType == CallType.GROUP) {
            conversationId?.let { For.Group(it) }
        } else {
            callerId.let { For.Account(it) }
        }

        callMessageCreator.createCallMessage(
            forWhat,
            callType,
            callRole,
            CallActionType.REJECT,
            conversationId,
            null,
            listOf(roomId),
            null,
            mySelfId,
            null
        ).concatMap { callEncryptResult ->
            val body = ControlMessageRequestBody(
                roomId = roomId,
                System.currentTimeMillis(),
                cipherMessages = callEncryptResult.cipherMessages,
                detailMessageType = if (callType == CallType.ONE_ON_ONE) DetailMessageType.CallEnd.value else DetailMessageType.Unknown.value
            )
            callService.controlMessages(SecureSharedPrefsUtil.getToken(), body)
        }
            .compose(RxUtil.getSingleSchedulerComposer())
            .doFinally { onComplete() }
            .autoDispose(autoDisposeCompletable)
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { data ->
                        L.d { "[Call] rejectCall, request success, response data:$data" }
                    }
                } else {
                    L.e { "[Call] rejectCall, response status fail, reason:${it.reason}" }
                }
            }, {
                L.e { "[Call] rejectCall, request fail, error:${it.message}" }
            })

    }

    override fun cancelCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?, onComplete: () -> Unit) {
        val callType = CallType.fromString(type) ?: CallType.ONE_ON_ONE
        val forWhat = if (callType == CallType.GROUP) {
            conversationId?.let { For.Group(it) }
        } else {
            conversationId?.let { For.Account(it) }
        }
        L.d { "[Call] cancelCall, params mySelfId:$mySelfId roomId:$roomId callerId:$callerId type:$type conversationId:$conversationId" }

        callMessageCreator.createCallMessage(
            forWhat,
            callType,
            callRole,
            CallActionType.CANCEL,
            conversationId,
            null,
            listOf(roomId),
            null,
            mySelfId,
            null
        ).concatMap { callEncryptResult ->
            val body = ControlMessageRequestBody(
                roomId = roomId,
                System.currentTimeMillis(),
                cipherMessages = callEncryptResult.cipherMessages,
                detailMessageType = if (callType == CallType.ONE_ON_ONE) DetailMessageType.CallEnd.value else DetailMessageType.Unknown.value
            )
            callService.controlMessages(SecureSharedPrefsUtil.getToken(), body)
        }
            .compose(RxUtil.getSingleSchedulerComposer())
            .doFinally { onComplete() }
            .autoDispose(autoDisposeCompletable)
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { data ->
                        L.d { "[Call] cancelCall, request success, response data:$data" }
                    }
                } else {
                    L.e { "[Call] cancelCall, response status fail, reason:${it.reason}" }
                }
            }, {
                L.e { "[Call] cancelCall, request fail, error:${it.message}" }
            })

    }

    override fun hangUpCall(
        callerId: String,
        callRole: CallRole?,
        type: String,
        roomId: String,
        conversationId: String?,
        callUidList: List<String>,
        onComplete: () -> Unit
    ) {
        val callType = CallType.fromString(type) ?: CallType.ONE_ON_ONE
        val forWhat = when (callType) {
            CallType.GROUP -> {
                conversationId?.let { groupId -> For.Group(groupId) }
            }

            CallType.ONE_ON_ONE -> {
                if (callRole == CallRole.CALLER) For.Account(conversationId!!) else For.Account(callerId)
            }

            else -> {
                For.Account(callerId)
            }
        }

        L.d { "[Call] hangUpCall, params mySelfId:$mySelfId roomId:$roomId callerId:$callerId type:$type conversationId:$conversationId" }
        callMessageCreator.createCallMessage(
            forWhat,
            callType,
            callRole,
            CallActionType.HANGUP,
            conversationId,
            null,
            listOf(roomId),
            null,
            callerId,
            null,
            callUidList,
        ).concatMap { callEncryptResult ->
            val body = ControlMessageRequestBody(
                roomId = roomId,
                System.currentTimeMillis(),
                cipherMessages = callEncryptResult.cipherMessages,
                detailMessageType = if (callType == CallType.ONE_ON_ONE) DetailMessageType.CallEnd.value else DetailMessageType.GroupCallEnd.value
            )
            callService.controlMessages(SecureSharedPrefsUtil.getToken(), body)
        }
            .compose(RxUtil.getSingleSchedulerComposer())
            .doFinally { onComplete() }
            .autoDispose(autoDisposeCompletable)
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { data ->
                        L.d { "[Call] hangUpCall, request success, response data:$data" }
                    }
                } else {
                    L.e { "[Call] hangUpCall, response status fail, reason:${it.reason}" }
                }
            }, {
                L.e { "[Call] hangUpCall, request fail, error:${it.message}" }
            })

    }

    override fun syncJoinedMessage(receiverId: String, callRole: CallRole?, callerId: String, type: String, roomId: String, conversationId: String?, mKey: ByteArray?) {
        val forWhat = For.Account(receiverId)
        val callType = CallType.fromString(type) ?: CallType.ONE_ON_ONE
        callMessageCreator.createCallMessage(
            forWhat,
            callType,
            callRole,
            CallActionType.JOINED,
            conversationId,
            null,
            listOf(roomId),
            null,
            callerId,
            mKey
        ).concatMap { callEncryptResult ->
            val body = ControlMessageRequestBody(
                roomId = roomId,
                System.currentTimeMillis(),
                cipherMessages = callEncryptResult.cipherMessages,
            )
            callService.controlMessages(SecureSharedPrefsUtil.getToken(), body)
        }
            .compose(RxUtil.getSingleSchedulerComposer())
            .autoDispose(autoDisposeCompletable)
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { data ->
                        L.d { "[Call] syncJoinedMessage, request success, response data:$data" }
                    }
                } else {
                    L.e { "[Call] syncJoinedMessage, response status fail, reason:${it.reason}" }
                }
            }, {
                L.e { "[Call] syncJoinedMessage, request fail, error:${it.message}" }
            })
    }

    override suspend fun getContactorById(context: Context, id: String): Optional<ContactorModel> {
        return ContactorUtil.getContactWithID(context, id).await()
    }

    override fun getDisplayName(context: Context, id: String): String? {
        var userId = id
        if (userId.contains(".")) {
            userId = userId.split(".")[0]
        }
        var displayName: String? = null
        val contactor = ContactorUtil.getContactWithID(context, userId).blockingGet()
        if (contactor.isPresent) {
            displayName = contactor.get().getDisplayNameForUI()
        }
        return displayName
    }

    override fun getAvatarByContactor(context: Context, contactor: ContactorModel): ConstraintLayout {

        val avatarView = AvatarView(context)

        avatarView.setAvatar(contactor)

        return avatarView
    }

    override fun createAvatarByNameOrUid(context: Context, name: String?, uid: String): ConstraintLayout {
        var userId = uid
        if (userId.contains(".")) {
            userId = userId.split(".")[0]
        }
        val firstLetter = if (!name.isNullOrEmpty()) {
            name.substring(0, 1)
        } else {
            IdUtil.convertToBase58UserName(userId).let {
                if (!it.isNullOrEmpty()) {
                    it.substring(0, 1)
                } else {
                    userId
                }
            }
        }

        val avatarView = AvatarView(context)
        avatarView.setAvatar(
            null,
            null,
            firstLetter,
            userId.replace("+", "")
        )
        return avatarView
    }

    override fun getMySelfUid(): String {
        return mySelfId
    }

    override suspend fun getSingleGroupInfo(context: Context, conversationId: String): Optional<GroupModel> {
        return GroupUtil.getSingleGroupInfo(context, conversationId).firstOrError().await()
    }

    override fun cancelNotificationById(notificationId: Int) {
        if (messageNotificationUtil.isNotificationShowing(notificationId)) {
            messageNotificationUtil.cancelNotificationsById(notificationId)
        }
    }

    override fun showCallNotification(
        roomId: String,
        callName: String,
        callerId: String,
        conversationId: String?,
        callType: CallType,
        isNeedAppLock: Boolean
    ) {
        return messageNotificationUtil.showCallNotificationNew(roomId, callName, callerId, conversationId, callType, isNeedAppLock)
    }

    /**
     * Checks if a notification with the given ID is currently being displayed
     * @param notificationId The ID of the notification to check
     * @return true if the notification is showing, false otherwise
     */
    override fun isNotificationShowing(notificationId: Int): Boolean {
        return messageNotificationUtil.isNotificationShowing(notificationId)
    }

    override fun sendOrCreateCallTextMessage(
        callActionType: CallActionType,
        textContent: String,
        sourceDevice: Int,
        timestamp: Long,
        systemShowTime: Long,
        fromWho: For,
        forWhat: For,
        callType: CallType,
        createCallMsg: Boolean,
        inviteeLIst: List<String>
    ) {
        appScope.launch {
            runCatching {
                val textMessage = localMessageCreator.createCallTextMessage(
                    callActionType = callActionType,
                    textContent = textContent,
                    sourceDevice = sourceDevice,
                    timestamp = timestamp,
                    systemShowTime = systemShowTime,
                    fromWho = fromWho,
                    forWhat = forWhat,
                    inviteeList = inviteeLIst,
                    saveToLocal = createCallMsg
                )
                if (!createCallMsg) {
                    // 会议发起方需发送call展示消息
                    ApplicationDependencies.getJobManager().add(
                        pushTextJobFactory.create(null, textMessage, null)
                    )
                }
            }.onFailure { e ->
                L.e(e) { "[Call] sendOrCreateCallTextMessage Error:" }
            }
        }
    }

    override fun sendScreenshotNotification(conversationId: String, callType: CallType) {
        if (conversationId.isBlank()) {
            return
        }

        appScope.launch {
            runCatching {
                val forWhat = if (callType == CallType.GROUP) {
                    For.Group(conversationId)
                } else {
                    For.Account(conversationId)
                }
                val timeStamp = System.currentTimeMillis()
                val messageId = "${timeStamp}${mySelfId.replace("+", "")}${DEFAULT_DEVICE_ID}"
                val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).await().toInt()
                val screenShot = ScreenShot(
                    RealSource(mySelfId, DEFAULT_DEVICE_ID, timeStamp, timeStamp)
                )
                val screenshotText = application.getString(
                    com.difft.android.chat.R.string.chat_took_a_screen_shot,
                    application.getString(com.difft.android.chat.R.string.you)
                )
                val textMessage = TextMessage(
                    messageId,
                    For.Account(mySelfId),
                    forWhat,
                    timeStamp,
                    timeStamp,
                    System.currentTimeMillis(),
                    SendType.Sending.rawValue,
                    expiresInSeconds,
                    0,
                    0,
                    0,
                    screenshotText,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    screenShot,
                    null
                )
                ApplicationDependencies.getJobManager().add(
                    pushTextJobFactory.create(null, textMessage, null)
                )
            }.onFailure { e ->
                L.e(e) { "[Call] sendScreenshotNotification Error:" }
            }
        }
    }

    /**
     * 创建本地 Critical Alert 文本消息
     * @param systemShowTimestamp 服务端系统时间戳
     * @param timestamp 消息时间戳
     * @param fromWho 消息发送者
     * @param forWhat 消息所属会话
     * @param sourceDevice 消息所属设备类型
     */
    override suspend fun createCriticalAlertMessage(systemShowTimestamp: Long, timestamp: Long, fromWho: For, forWhat: For, sourceDevice: Int) {
        localMessageCreator.createCriticalAlertMessage(systemShowTimestamp, timestamp, fromWho, forWhat, sourceDevice)
    }

    /**
     * Returns the local private key used for secure communication.
     * @return ByteArray containing the private key, or null if not available
     */
    override fun getLocalPrivateKey(): ByteArray? {
        return encryptionDataManager.getAciIdentityKey().privateKey.serialize()
    }

    /**
     * Retrieves the public key for a given user ID.
     * @param uid Unique identifier of the user
     * @return String containing the public key, or null if not found
     */
    override fun getTheirPublicKey(uid: String): String? {
        var userId = uid
        if (userId.contains(".")) {
            userId = userId.split(".")[0]
        }

        if (!ValidatorUtil.isUid(userId)) {
            L.e { "[Call] geTheirPublicKey Error: $userId is not a valid uid" }
            return null
        }

        val publicKeyInfos: List<PublicKeyInfo>? = conversationManager.getPublicKeyInfos(listOf(userId))
        if (publicKeyInfos.isNullOrEmpty()) {
            L.e { "[Call] geTheirPublicKey Error: $userId get public key is null" }
            return null
        }

        val publicKeyInfo = publicKeyInfos.firstOrNull { it.uid == userId }
        if (publicKeyInfo == null) {
            L.e { "[Call] geTheirPublicKey Error: $userId not found in public key list" }
            return null
        }

        if (publicKeyInfo.identityKey.isBlank()) {
            L.e { "[Call] geTheirPublicKey Error: $userId identityKey is empty or blank" }
            return null
        }

        return publicKeyInfo.identityKey
    }

    override fun restoreIncomingCallScreenIfActive() {
        L.i { "[Call] Status: inCalling=${inComingCallStateManager.isActivityShowing()}, isInForeground=${inComingCallStateManager.isInForeground()}" }
        if (inComingCallStateManager.isActivityShowing() && !inComingCallStateManager.isInForeground() && !ScreenDeviceUtil.isScreenLocked(application)) {
            L.i { "[Call] Status: OK> restoreIncomingCallActivityIfIncoming" }
            val intent = Intent(application, LIncomingCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            application.startActivity(intent)
        }
    }

    /**
     * Determines if the application is currently in the foreground
     * @return true if app is foregrounded, false if in background
     */
    override fun isAppForegrounded(): Boolean {
        return AppForegroundObserver.isForegrounded()
    }

    /**
     * Checks if the incoming call activity screen is currently visible
     * @return true if incoming call screen is showing, false otherwise
     */
    override fun isIncomingCallActivityShowing(): Boolean {
        return inComingCallStateManager.isActivityShowing()
    }

    override fun isIncomingCallNotifying(roomId: String): Boolean {
        return inComingCallStateManager.getCurrentRoomId() == roomId || isNotificationShowing(roomId.hashCode())
    }

    /**
     * Returns an Observable that emits updates when contacts change
     * @return Observable that emits a list of contact IDs that were updated
     */
    override fun getContactsUpdateListener(): Observable<List<String>> {
        return ContactorUtil.contactsUpdate.hide()
    }

    override fun getGroupsUpdateListener(): Observable<GroupModel> {
        return GroupUtil.singleGroupsUpdate.hide()
    }

    override fun startForegroundService(
        context: Context,
        intent: Intent,
    ) {
        try {
            ForegroundServiceUtil.start(context, intent)
        } catch (e: UnableToStartException) {
            L.w { "[MessageForegroundService] Unable to start foreground service for websocket. Deferring to background to try with blocking" }
            TTExecutors.UNBOUNDED.execute {
                try {
                    ForegroundServiceUtil.startWhenCapable(context, intent)
                } catch (e: UnableToStartException) {
                    L.w(e) { "[MessageForegroundService] Unable to start foreground service for websocket!" }
                }
            }
        }
    }

    override fun getIncomingCallRoomId(): String? {
        return inComingCallStateManager.getCurrentRoomId()
    }

    override fun dismissCriticalAlertIfActive() {
        if (criticalAlertStateManager.isShowing()) {
            val intent = Intent(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS).apply {
                `package` = application.packageName
            }
            application.sendBroadcast(intent)
        } else {
            messageNotificationUtil.cancelCriticalAlertNotification()
        }
    }

    override fun dismissCriticalAlert(conversationId: String) {
        if (criticalAlertStateManager.isShowing()) {
            val intent = Intent(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_CONID).apply {
                `package` = application.packageName
                putExtra(LCallConstants.CRITICAL_ALERT_PARAM_CONVERSATION, conversationId)
            }
            application.sendBroadcast(intent)
        }
    }

    override fun cancelCriticalAlertNotification(conversationId: String?) {
        messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
    }

    private suspend fun startCallInternal(context: Context, intent: Intent, onComplete: (Boolean) -> Unit) {
        withContext(Dispatchers.Main) {
            try {
                ScreenLockUtil.temporarilyDisabled = true
                context.applicationContext.startActivity(intent)
                onComplete(true)
            } catch (e: Exception) {
                L.e(e) { "[Call] joinCall startCallInternal failed:" }
                CallWaitDialogUtil.dismiss()
                onComplete(false)
            }
        }
    }

    /**
     * 验证邀请通话参数
     */
    private fun validateInviteCallParams(roomId: String, inviteMembers: ArrayList<String>): Boolean {
        if (roomId.isEmpty() || inviteMembers.isEmpty()) {
            ToastUtil.show("roomId or invite members is empty")
            return false
        }
        return true
    }

    /**
     * 从字符串解析通话类型
     */
    private fun resolveCallTypeFromString(callType: String?): CallType {
        return if (!callType.isNullOrEmpty()) {
            CallType.fromString(callType) ?: CallType.INSTANT
        } else {
            CallType.INSTANT
        }
    }

    /**
     * 创建邀请通话请求体
     */
    private fun createInviteCallRequest(
        roomId: String,
        callEncryptResult: CallEncryptResult
    ): InviteCallRequestBody {
        val collapseId = MD5Utils.md5AndHexStr(
            System.currentTimeMillis().toString() + mySelfId + DEFAULT_DEVICE_ID
        )
        val notification = Notification(Args(collapseId), INVITE_NOTIFICATION_TYPE)

        return InviteCallRequestBody(
            roomId = roomId,
            timestamp = System.currentTimeMillis(),
            cipherMessages = callEncryptResult.cipherMessages,
            encInfos = callEncryptResult.encInfos,
            notification = notification,
            publicKey = callEncryptResult.publicKey
        )
    }

    /**
     * 处理邀请通话成功
     */
    private fun handleInviteCallSuccess(
        responseData: com.difft.android.base.call.InviteCallResponseData?,
        inviteMembers: ArrayList<String>,
        callType: CallType,
        createdAt: Long
    ) {
        appScope.launch {
            val textContent = ApplicationHelper.instance.getString(
                R.string.call_invite_send_message,
                contactorCacheManager.getDisplayName(mySelfId)
            )
            val systemShowTimestamp = responseData?.systemShowTimestamp ?: createdAt

            inviteMembers.forEach { uid ->
                val forWhat = For.Account(uid)
                LCallManager.sendOrLocalCallTextMessage(
                    CallActionType.INVITE, textContent, DEFAULT_DEVICE_ID,
                    createdAt, systemShowTimestamp,
                    For.Account(mySelfId), forWhat, callType,
                    callConfig.createCallMsg, inviteMembers
                )
            }
        }
    }

    /**
     * 处理邀请通话失败
     */
    private fun handleInviteCallFailure(
        invalidUids: List<String>?,
        stale: List<com.difft.android.base.call.Stale>?
    ) {
        when {
            invalidUids != null -> {
                appScope.launch {
                    val inviteFailedNames = invalidUids.map { uid ->
                        contactorCacheManager.getDisplayNameById(uid)
                    }.joinToString(",")

                    withContext(Dispatchers.Main) {
                        ToastUtil.show("Invite failed for: $inviteFailedNames")
                    }
                }
            }
            stale != null -> {
                appScope.launch {
                    val inviteFailedNames = stale.map { staleData ->
                        contactorCacheManager.getDisplayNameById(staleData.uid)
                    }.joinToString(",")

                    withContext(Dispatchers.Main) {
                        ToastUtil.show("Invite failed for: $inviteFailedNames")
                    }
                }
            }
            else -> {
                ToastUtil.show(R.string.call_invite_fail_tip)
            }
        }
    }

    /**
     * 处理邀请通话的响应
     */
    private fun handleInviteCallResponse(
        response: BaseResponse<com.difft.android.base.call.InviteCallResponseData>,
        inviteMembers: ArrayList<String>,
        callType: CallType,
        createdAt: Long,
        callback: (InviteRequestState) -> Unit
    ) {
        when (response.status) {
            RESPONSE_STATUS_SUCCESS -> {
                L.d { "[Call] inviteCall, invite success, response: reason:${response.reason}, data:${response.data}" }
                handleInviteCallSuccess(response.data, inviteMembers, callType, createdAt)
                callback(InviteRequestState.SUCCESS)
            }
            RESPONSE_STATUS_INVALID_UIDS -> {
                L.e { "[Call] inviteCall, invite failed, response: status:${response.status}, reason:${response.reason}, data:${response.data}" }
                handleInviteCallFailure(response.data?.invalidUids, null)
                callback(InviteRequestState.FAILED)
            }
            RESPONSE_STATUS_STALE -> {
                L.e { "[Call] inviteCall, invite failed, response: status:${response.status}, reason:${response.reason}, data:${response.data}" }
                handleInviteCallFailure(null, response.data?.stale)
                callback(InviteRequestState.FAILED)
            }
            else -> {
                L.e { "[Call] inviteCall, invite failed, response: status:${response.status}, reason:${response.reason}, data:${response.data}" }
                callback(InviteRequestState.FAILED)
                ToastUtil.show(R.string.call_invite_fail_tip)
            }
        }
    }

    /**
     * 处理邀请通话错误
     */
    private fun handleInviteCallError(error: Throwable) {
        L.e { "[Call] inviteCall, request fail, error:${error.stackTraceToString()}" }
        error.message?.let { message ->
            ToastUtil.show(message)
        }
    }


    override fun inviteCall(
        roomId: String,
        roomName: String?,
        callType: String?,
        mKey: ByteArray?,
        inviteMembers: ArrayList<String>,
        conversationId: String?,
        callback: (InviteRequestState) -> Unit
    ) {
        if (!validateInviteCallParams(roomId, inviteMembers)) {
            return
        }

        L.d { "[Call] inviteCall, params roomId:$roomId, roomName:$roomName callType:$callType" }

        val type = resolveCallTypeFromString(callType)
        val createdAt = System.currentTimeMillis()

        appScope.launch(Dispatchers.IO) {
            try {
                // 创建通话消息
                val callEncryptResult = callMessageCreator.createCallMessage(
                    null,
                    type,
                    null,
                    CallActionType.INVITE,
                    conversationId = if (type == CallType.INSTANT) null else conversationId,
                    inviteMembers,
                    listOf(roomId),
                    roomName,
                    mySelfId,
                    mKey,
                    createCallMsg = callConfig.createCallMsg,
                    createdAt = createdAt,
                ).await()

                // 创建请求体并发送邀请
                val requestBody = createInviteCallRequest(roomId, callEncryptResult)
                val response = callService.inviteCall(SecureSharedPrefsUtil.getToken(), requestBody)
                    .await()

                handleInviteCallResponse(response, inviteMembers, type, createdAt, callback)
            } catch (error: Exception) {
                handleInviteCallError(error)
                callback(InviteRequestState.FAILED)
            }
        }
    }

    override fun isBotId(id: String): Boolean {
        return id.length <= LENGTH_OF_BOT_ID
    }

    override fun contactorListSortedByPinyin(list : List<ContactorModel>): List<ContactorModel> {
        return list.sortedByPinyin()
    }

}