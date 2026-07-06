package com.difft.android.call

import android.content.Context
import android.content.Intent
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.StartCallRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.CountdownTimer
import com.difft.android.base.user.PromptReminder
import com.difft.android.base.user.defaultBarrageTexts
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.call.data.createStartCallParams
import com.difft.android.call.handler.InviteRequestState
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.ScreenDeviceUtil
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.contactsall.sortedByPinyin
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.LENGTH_OF_BOT_ID
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.util.ForegroundServiceUtil
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.UnableToStartException
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.config.WsTokenManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.websocket.api.ConversationManager
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.util.CallMessageCreator
import dagger.Lazy
import difft.android.messageserialization.For
import difft.android.messageserialization.model.RealSource
import difft.android.messageserialization.model.ScreenShot
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import util.AppForegroundObserver
import util.ScreenLockUtil
import java.util.ArrayList
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

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
    private val groupUtil: GroupUtil,
) : LCallToChatController {

    private val callService by lazy { httpClient.getService(LCallHttpService::class.java) }
    private val mySelfId: String by lazy { globalServices.myId }
    private val callDataManager: CallDataManager by lazy { callDataManagerLazy.get() }

    private val callConfig: CallConfig by lazy {
        globalConfigsManager.getNewGlobalConfigs()?.data?.call ?: CallConfig(
            autoLeave = AutoLeave(promptReminder = PromptReminder()),
            chatPresets = defaultBarrageTexts,
            chat = CallChat(),
            countdownTimer = CountdownTimer(),
        )
    }

    private val controlMessageSender by lazy {
        CallControlMessageSender(callService, callMessageCreator, mySelfId)
    }

    private val inviteExecutor by lazy {
        CallInviteExecutor(callService, callMessageCreator, callConfig, mySelfId, contactorCacheManager)
    }

    // region Call signaling

    override suspend fun joinCall(
        context: Context,
        roomId: String,
        roomName: String?,
        callerId: String,
        callType: CallType,
        conversationId: String?,
        isNeedAppLock: Boolean,
    ): Boolean {
        withContext(Dispatchers.Main) { CallWaitDialogUtil.show(context) }

        return try {
            withContext(Dispatchers.IO) {
                conversationId?.let { messageNotificationUtil.cancelCriticalAlertNotification(it) }
                dismissCriticalAlertIfActive()
                conversationId?.let { dbRoomStore.clearCriticalAlert(it) }

                val body = StartCallRequestBody(
                    callType.type, LCallConstants.CALL_VERSION,
                    System.currentTimeMillis(),
                    conversation = conversationId, roomId = roomId,
                )

                val callData = callDataManager.getCallData(roomId)
                val callRole = if (callData?.caller?.uid == mySelfId && callData.caller.did == DEFAULT_DEVICE_ID)
                    CallRole.CALLER else CallRole.CALLEE

                val startCallParams = createStartCallParams(body)
                wsTokenManager.refreshTokenIfNeeded()
                LCallManager.checkQuicFeatureGrayStatus()

                val intent = CallIntent.Builder(application, LCallActivity::class.java)
                    .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .withAction(CallIntent.Action.JOIN_CALL)
                    .withRoomName(roomName)
                    .withCallType(callType.type)
                    .withCallRole(callRole.type)
                    .withCallerId(mySelfId)
                    .withConversationId(conversationId)
                    .withStartCallParams(startCallParams)
                    .withNeedAppLock(isNeedAppLock)
                    .withCallWaitDialogShown(true)
                    .build()

                startCallInternal(context, intent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e(e) { "[Call] joinCall unexpected error:" }
            withContext(Dispatchers.Main) { CallWaitDialogUtil.dismiss() }
            false
        }
    }

    override suspend fun rejectCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?) =
        controlMessageSender.rejectCall(callerId, callRole, type, roomId, conversationId)

    override suspend fun cancelCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?) =
        controlMessageSender.cancelCall(callerId, callRole, type, roomId, conversationId)

    override suspend fun hangUpCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?, callUidList: List<String>) =
        controlMessageSender.hangUpCall(callerId, callRole, type, roomId, conversationId, callUidList)

    override fun syncJoinedMessage(receiverId: String, callRole: CallRole?, callerId: String, type: String, roomId: String, conversationId: String?, mKey: ByteArray?) =
        controlMessageSender.syncJoinedMessage(receiverId, callRole, callerId, type, roomId, conversationId, mKey)

    override suspend fun inviteCall(roomId: String, roomName: String?, callType: String?, mKey: ByteArray?, inviteMembers: ArrayList<String>, conversationId: String?): InviteRequestState =
        inviteExecutor.inviteCall(roomId, roomName, callType, mKey, inviteMembers, conversationId)

    // endregion

    // region Contact & display

    override suspend fun getContactorById(context: Context, id: String): Optional<ContactorModel> =
        ContactorUtil.getContactWithID(context, id)

    override fun getAvatarByContactor(context: Context, contactor: ContactorModel): ConstraintLayout =
        AvatarView(context).apply { setAvatar(contactor) }

    override fun createAvatarByNameOrUid(context: Context, name: String?, uid: String): ConstraintLayout {
        val userId = if (uid.contains(".")) uid.split(".")[0] else uid
        val firstLetter = if (!name.isNullOrEmpty()) {
            name.take(1)
        } else {
            IdUtil.convertToBase58UserName(userId)?.takeIf { it.isNotEmpty() }?.take(1) ?: userId
        }
        return AvatarView(context).apply { setAvatar(null, null, firstLetter, userId.replace("+", "")) }
    }

    override fun getMySelfUid(): String = mySelfId

    override suspend fun getSingleGroupInfo(conversationId: String): GroupModel? =
        groupUtil.getSingleGroupInfo(conversationId)

    override fun isBotId(id: String): Boolean = id.length <= LENGTH_OF_BOT_ID

    override fun contactorListSortedByPinyin(list: List<ContactorModel>): List<ContactorModel> = list.sortedByPinyin()

    // endregion

    // region Notification

    override fun cancelNotificationById(notificationId: Int) {
        if (messageNotificationUtil.hasPendingCallNotification(notificationId) ||
            messageNotificationUtil.isNotificationShowing(notificationId)) {
            messageNotificationUtil.cancelNotificationsById(notificationId)
        }
    }

    override fun showCallNotification(roomId: String, callName: String, callerId: String, conversationId: String?, callType: CallType, isNeedAppLock: Boolean) =
        messageNotificationUtil.showCallNotificationNew(roomId, callName, callerId, conversationId, callType, isNeedAppLock)

    override fun isNotificationShowing(notificationId: Int): Boolean =
        messageNotificationUtil.isNotificationShowing(notificationId)

    // endregion

    // region Message

    override fun sendOrCreateCallTextMessage(
        callActionType: CallActionType, textContent: String, sourceDevice: Int,
        timestamp: Long, systemShowTime: Long, fromWho: For, forWhat: For,
        callType: CallType, createCallMsg: Boolean, inviteeLIst: List<String>,
    ) {
        appScope.launch {
            runCatching {
                val textMessage = localMessageCreator.createCallTextMessage(
                    callActionType = callActionType, textContent = textContent,
                    sourceDevice = sourceDevice, timestamp = timestamp,
                    systemShowTime = systemShowTime, fromWho = fromWho, forWhat = forWhat,
                    inviteeList = inviteeLIst, saveToLocal = createCallMsg,
                )
                if (!createCallMsg) {
                    ApplicationDependencies.getJobManager().add(
                        pushTextJobFactory.create(null, textMessage, null)
                    )
                }
            }.onFailure { e -> L.e(e) { "[Call] sendOrCreateCallTextMessage Error:" } }
        }
    }

    override fun sendScreenshotNotification(conversationId: String, callType: CallType) {
        if (conversationId.isBlank()) return
        appScope.launch {
            runCatching {
                val forWhat = if (callType == CallType.GROUP) For.Group(conversationId) else For.Account(conversationId)
                val timeStamp = System.currentTimeMillis()
                val messageId = "${timeStamp}${mySelfId.replace("+", "")}${DEFAULT_DEVICE_ID}"
                val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).toInt()
                val screenShot = ScreenShot(RealSource(mySelfId, DEFAULT_DEVICE_ID, timeStamp, timeStamp))
                val screenshotText = application.getString(
                    com.difft.android.chat.R.string.chat_took_a_screen_shot,
                    application.getString(com.difft.android.chat.R.string.you),
                )
                val textMessage = TextMessage(
                    messageId, For.Account(mySelfId), forWhat,
                    timeStamp, timeStamp, System.currentTimeMillis(),
                    SendType.Sending.rawValue, expiresInSeconds,
                    0, 0, 0, screenshotText,
                    null, null, null, null, null, null, null, screenShot, null,
                )
                ApplicationDependencies.getJobManager().add(
                    pushTextJobFactory.create(null, textMessage, null)
                )
            }.onFailure { e -> L.e(e) { "[Call] sendScreenshotNotification Error:" } }
        }
    }

    override suspend fun createCriticalAlertMessage(systemShowTimestamp: Long, timestamp: Long, fromWho: For, forWhat: For, sourceDevice: Int) {
        localMessageCreator.createCriticalAlertMessage(systemShowTimestamp, timestamp, fromWho, forWhat, sourceDevice)
    }

    // endregion

    // region Encryption

    override fun getLocalPrivateKey(): ByteArray? =
        encryptionDataManager.getAciIdentityKey().privateKey.serialize()

    // Invoked on the RTM SDK's background dispatcher thread; never runs on Main.
    @Suppress("BanRunBlockingOutsideTests")
    override fun getTheirPublicKey(uid: String): String? {
        val userId = if (uid.contains(".")) uid.split(".")[0] else uid
        if (!ValidatorUtil.isUid(userId)) {
            L.e { "[Call] geTheirPublicKey Error: $userId is not a valid uid" }
            return null
        }
        val publicKeyInfos: List<PublicKeyInfo>? = kotlinx.coroutines.runBlocking {
            conversationManager.getPublicKeyInfos(listOf(userId))
        }
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

    // endregion

    // region Incoming call state

    override fun restoreIncomingCallScreenIfActive() {
        L.i { "[Call] Status: inCalling=${inComingCallStateManager.isActivityShowing()}, isInForeground=${inComingCallStateManager.isInForeground()}" }
        if (!inComingCallStateManager.isActivityShowing() || inComingCallStateManager.isInForeground()) return
        appScope.launch(Dispatchers.IO) {
            val isLocked = ScreenDeviceUtil.isScreenLocked(application)
            if (!isLocked && inComingCallStateManager.isActivityShowing() && !inComingCallStateManager.isInForeground()) {
                L.i { "[Call] Status: OK> restoreIncomingCallActivityIfIncoming" }
                withContext(Dispatchers.Main) {
                    val intent = Intent(application, LIncomingCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    application.startActivity(intent)
                }
            }
        }
    }

    override fun isAppForegrounded(): Boolean = AppForegroundObserver.isForegrounded()

    override fun isIncomingCallActivityShowing(): Boolean = inComingCallStateManager.isActivityShowing()

    override fun isIncomingCallNotifying(roomId: String): Boolean =
        inComingCallStateManager.getCurrentRoomId() == roomId || isNotificationShowing(roomId.hashCode())

    override fun getContactsUpdateListener(): Flow<List<String>> = ContactorUtil.contactsUpdate

    override fun getGroupsUpdateListener(): Flow<GroupModel> = groupUtil.singleGroupsUpdate

    override fun getIncomingCallRoomId(): String? = inComingCallStateManager.getCurrentRoomId()

    // endregion

    // region Service & critical alert

    override fun startForegroundService(context: Context, intent: Intent) {
        try {
            ForegroundServiceUtil.start(context, intent)
        } catch (_: UnableToStartException) {
            L.w { "[MessageForegroundService] Unable to start foreground service for websocket. Deferring to background to try with blocking" }
            appScope.launch(Dispatchers.IO) {
                try {
                    ForegroundServiceUtil.startWhenCapable(context, intent)
                } catch (e: UnableToStartException) {
                    L.w(e) { "[MessageForegroundService] Unable to start foreground service for websocket!" }
                }
            }
        }
    }

    override fun dismissCriticalAlertIfActive() {
        if (criticalAlertStateManager.isShowing()) {
            application.sendBroadcast(Intent(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS).apply {
                `package` = application.packageName
            })
        } else {
            messageNotificationUtil.cancelCriticalAlertNotification()
        }
    }

    override fun dismissCriticalAlert(conversationId: String) {
        if (criticalAlertStateManager.isShowing()) {
            application.sendBroadcast(Intent(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_CONID).apply {
                `package` = application.packageName
                putExtra(LCallConstants.CRITICAL_ALERT_PARAM_CONVERSATION, conversationId)
            })
        }
    }

    override fun cancelCriticalAlertNotification(conversationId: String?) =
        messageNotificationUtil.cancelCriticalAlertNotification(conversationId)

    // endregion

    private suspend fun startCallInternal(context: Context, intent: Intent): Boolean =
        withContext(Dispatchers.Main) {
            try {
                ScreenLockUtil.temporarilyDisabled = true
                context.applicationContext.startActivity(intent)
                true
            } catch (e: Exception) {
                L.e(e) { "[Call] joinCall startCallInternal failed:" }
                CallWaitDialogUtil.dismiss()
                false
            }
        }
}
