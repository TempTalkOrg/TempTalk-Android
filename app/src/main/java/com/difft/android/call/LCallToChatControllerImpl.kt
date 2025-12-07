package com.difft.android.call

import android.content.Context
import android.content.Intent
import androidx.constraintlayout.widget.ConstraintLayout
import autodispose2.autoDispose
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.ControlMessageRequestBody
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.StartCallRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.application
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.recent.InviteParticipantsActivity
import com.difft.android.chat.setting.archive.MessageArchiveManager
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBMessageStore
import difft.android.messageserialization.model.TextMessage
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.CompletableSubject
import kotlinx.coroutines.rx3.await
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import util.concurrent.TTExecutors
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.util.AppForegroundObserver
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
import com.difft.android.network.config.WsTokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import util.ScreenLockUtil


class LCallToChatControllerImpl @Inject constructor(
    @ChativeHttpClientModule.Call
    private val httpClient: ChativeHttpClient,
    private val callMessageCreator: CallMessageCreator,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val messageArchiveManager: MessageArchiveManager,
    private val pushTextJobFactory: PushTextSendJobFactory,
    private val conversationManager: ConversationManager,
    private val dbMessageStore: DBMessageStore,
    private val encryptionDataManager: EncryptionDataManager,
    private val wsTokenManager: WsTokenManager
) : LCallToChatController {

    private val autoDisposeCompletable = CompletableSubject.create()

    private val callService by lazy {
        httpClient.getService(LCallHttpService::class.java)
    }

    private val mySelfId: String by lazy {
        globalServices.myId
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
        LCallManager.showWaitDialog(context)

        // 启动协程
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = StartCallRequestBody(
                    callType.type,
                    LCallConstants.CALL_VERSION,
                    System.currentTimeMillis(),
                    conversation = conversationId,
                    roomId = roomId
                )

                val callData = LCallManager.getCallData(roomId)
                val callRole = if (callData?.caller?.uid == mySelfId && callData.caller.did == DEFAULT_DEVICE_ID)
                    CallRole.CALLER
                else
                    CallRole.CALLEE

                val startCallParams = LCallManager.createStartCallParams(body)

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
                        onComplete(false)
                    }
                }.onFailure { e ->
                    L.e { "[Call] joinCall getCallServerUrl error:${e.message}" }
                    onComplete(false)
                }
            } catch (e: Exception) {
                L.e { "[Call] joinCall unexpected error:${e.message}" }
                onComplete(false)
            } finally {
                withContext(Dispatchers.Main) { LCallManager.dismissWaitDialog() }
            }
        }
    }

    override fun rejectCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?, onComplete: () -> Unit) {
        val callType = CallType.fromString(type)?:CallType.ONE_ON_ONE

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
        val callType = CallType.fromString(type)?:CallType.ONE_ON_ONE
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
                if(callRole == CallRole.CALLER) For.Account(conversationId!!) else For.Account(callerId)
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
        val callType = CallType.fromString(type)?:CallType.ONE_ON_ONE
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
        if (contactor.isPresent){
            displayName = contactor.get().getDisplayNameForUI()
        }
        return displayName
    }

    override fun getAvatarByContactor(context:Context, contactor: ContactorModel): ConstraintLayout {

        val avatarView = AvatarView(context)

        avatarView.setAvatar(contactor)

        return avatarView
    }

    override fun createAvatarByNameOrUid(context: Context, name: String?, uid: String): ConstraintLayout {
        var userId = uid
        if (userId.contains(".")) {
            userId = userId.split(".")[0]
        }
        val firstLetter = if(!name.isNullOrEmpty()){
            name.substring(0, 1)
        } else {
            LCallManager.convertToBase58UserName(userId).let {
                if(!it.isNullOrEmpty()){
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

    override fun getSingleGroupInfo(context: Context, conversationId: String): Optional<GroupModel> {
        return GroupUtil.getSingleGroupInfo(context, conversationId).blockingFirst()
    }


    override fun inviteUsersToTheCall(context: Context, roomId: String, roomName: String, e2eeKey: ByteArray?, callType: String, conversationId: String?, excludedIds: ArrayList<String>) {
        L.d { "[Call] inviteUsersToTheCall, params roomId:$roomId e2eeKey:${e2eeKey} callType:$callType conversationId:$conversationId excludedIds:${excludedIds}" }
        val intent = Intent(context, InviteParticipantsActivity::class.java)
        intent.putExtra(
            InviteParticipantsActivity.EXTRA_ACTION_TYPE,
            InviteParticipantsActivity.NEW_REQUEST_ACTION_TYPE_INVITE
        )
        intent.putExtra(
            InviteParticipantsActivity.EXTRA_ROOM_ID,
            roomId
        )
        intent.putExtra(
            InviteParticipantsActivity.EXTRA_CALL_NAME,
            roomName
        )
        intent.putExtra(
            InviteParticipantsActivity.EXTRA_E2EE_KEY,
            e2eeKey
        )
        intent.putExtra(
            InviteParticipantsActivity.EXTRA_CALL_TYPE,
            callType
        )
        intent.putExtra(
            InviteParticipantsActivity.EXTRA_CONVERSATION_ID,
            conversationId
        )
        intent.putExtra(
            InviteParticipantsActivity.EXTRA_SHOULD_BRING_CALL_SCREEN_BACK,
            true
        )
        intent.putStringArrayListExtra(
            InviteParticipantsActivity.EXTRA_EXCLUDE_IDS,
            excludedIds
        )
        context.startActivity(intent)
    }

    override fun cancelNotificationById(notificationId: Int) {
        if(messageNotificationUtil.isNotificationShowing(notificationId)){
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

        messageArchiveManager.getMessageArchiveTime(forWhat, false)
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .autoDispose(autoDisposeCompletable)
            .subscribe({ time ->

                val callMessageTime = when (callActionType) {
                    CallActionType.INVITE -> {
                        timestamp + inviteeLIst.indexOf(forWhat.id)
                    }
                    else -> {
                        timestamp
                    }
                }

                L.i { "[call] sendOrLocalCallTextMessage  callMessageTime:$callMessageTime forWhat.id:${forWhat.id}" }

                val messageId = StringBuilder().apply {
                    append(callMessageTime)
                    append(fromWho.id.replace("+", ""))
                    append(sourceDevice)
                }.toString()


                val textMessage = TextMessage(
                    id = messageId,
                    fromWho = fromWho,
                    forWhat = forWhat,
                    text = textContent,
                    systemShowTimestamp = systemShowTime,
                    timeStamp = callMessageTime,
                    receivedTimeStamp = System.currentTimeMillis(),
                    sendType = 1,
                    expiresInSeconds = time.toInt(),
                    notifySequenceId = 0,
                    sequenceId = 0,
                    atPersons = null,
                    quote = null,
                    forwardContext = null,
                    recall = null,
                    card = null,
                    mode = 0
                )

                if(createCallMsg){
                    // 客户端本地生成call消息
                    runCatching{
                        dbMessageStore.putWhenNonExist(textMessage)
                    }
//                    dbMessageStore.putMessage(textMessage).subscribeOn(Schedulers.io()).subscribe()
                }else {
                    // 会议发起方需发送call展示消息
                    ApplicationDependencies.getJobManager().add(
                        pushTextJobFactory.create(null, textMessage, null)
                    )
                }
            }, { error ->
                L.e { "[Call] pushCallTextMessage Error: ${error.message}" }
            })
    }

    /**
     * Returns the local private key used for secure communication.
     * @return ByteArray containing the private key, or null if not available
     */
    override fun getLocalPrivateKey(): ByteArray? {
        return  encryptionDataManager.getAciIdentityKey().privateKey.serialize()
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

        if(!ValidatorUtil.isUid(userId)){
            L.e { "[Call] geTheirPublicKey Error: $userId is not a valid uid" }
            return null
        }

        val publicKeyInfos: List<PublicKeyInfo>?  = conversationManager.getPublicKeyInfos(listOf(userId))
        if(publicKeyInfos.isNullOrEmpty()){
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

    override fun restoreIncomingCallActivityIfIncoming() {
        L.i { "[Call] Status: inCalling=${LIncomingCallActivity.isActivityShowing()}, isInForeground=${LIncomingCallActivity.isInForeground()}" }
        if (LIncomingCallActivity.isActivityShowing() && !LIncomingCallActivity.isInForeground() && !LCallManager.isScreenLocked(application)) {
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
        return LIncomingCallActivity.isActivityShowing()
    }

    override fun isIncomingCallNotifying(roomId: String): Boolean {
        return LIncomingCallActivity.getCurrentRoomId() == roomId || isNotificationShowing(roomId.hashCode())
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
                    L.w { "[MessageForegroundService] Unable to start foreground service for websocket!" + e.stackTraceToString() }
                }
            }
        }
    }

    override fun getIncomingCallRoomId(): String? {
        return LIncomingCallActivity.getCurrentRoomId()
    }

    private suspend fun startCallInternal(context: Context, intent: Intent, onComplete: (Boolean) -> Unit) {
        withContext(Dispatchers.Main) {
            try {
                ScreenLockUtil.temporarilyDisabled = true
                context.applicationContext.startActivity(intent)
                onComplete(true)
            } catch (e: Exception) {
                L.e { "[Call] joinCall startCallInternal failed: ${e.message}" }
                onComplete(false)
            }
        }
    }

}