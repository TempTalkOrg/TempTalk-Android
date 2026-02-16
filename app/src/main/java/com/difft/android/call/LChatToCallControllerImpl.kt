package com.difft.android.call

import android.app.Activity
import android.content.Intent
import com.difft.android.base.call.Args
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallEncryptResult
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
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
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.MD5Utils
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.call.repo.LCallHttpService
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.util.CallMessageCreator
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.call.data.createStartCallParams
import com.difft.android.call.handler.CallMessageHandler
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.network.config.WsTokenManager
import kotlinx.coroutines.rx3.await

@Singleton
class LChatToCallControllerImpl @Inject constructor(
    @param:ChativeHttpClientModule.Call
    private val httpClient: ChativeHttpClient,
    private val callMessageCreator: CallMessageCreator,
    private val messageEncryptor: INewMessageContentEncryptor,
    private val dbRoomStore: DBRoomStore,
    private val wsTokenManager: WsTokenManager,
    private val globalConfigsManager: GlobalConfigsManager,
    private val contactorCacheManager: ContactorCacheManager,
    private val callMessageHandler: CallMessageHandler,
) : LChatToCallController {

    private val callConfig: CallConfig by lazy {
        globalConfigsManager.getNewGlobalConfigs()?.data?.call ?: CallConfig(
            autoLeave = AutoLeave(promptReminder = PromptReminder()),
            chatPresets = defaultBarrageTexts,
            chat = CallChat(),
            countdownTimer = CountdownTimer()
        )
    }

    private val callService by lazy {
        httpClient.getService(LCallHttpService::class.java)
    }

    private val mySelfId: String by lazy {
        globalServices.myId
    }


    // 常量定义
    companion object {
        private const val RESPONSE_STATUS_SUCCESS = 0
    }

    override fun startCall(
        activity: Activity,
        forWhat: For,
        chatRoomName: String?,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if(!LCallEngine.isNetworkAvailable()) {
            onComplete(false, ResUtils.getString(R.string.call_no_internet_connection_tip))
            return
        }

        appScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    CallWaitDialogUtil.show(activity)
                }

                // 确保 Token 有效
                wsTokenManager.refreshTokenIfNeeded()

                dbRoomStore.createRoomIfNotExist(forWhat)

                LCallManager.checkQuicFeatureGrayStatus()

                val mySelfName = contactorCacheManager.getDisplayName(mySelfId)
                val token = SecureSharedPrefsUtil.getToken()

                val callEncryptResult = callMessageCreator.createCallMessage(
                    forWhat = forWhat,
                    callType = resolveCallType(forWhat),
                    callRole = CallRole.CALLER,
                    callActionType = CallActionType.START,
                    conversationId = forWhat.id,
                    members = null,
                    roomId = null,
                    roomName = if (resolveCallType(forWhat) == CallType.ONE_ON_ONE) mySelfName else chatRoomName,
                    caller = mySelfId,
                    mKey = messageEncryptor.generateKey(),
                    createCallMsg = callConfig.createCallMsg,
                    createdAt = System.currentTimeMillis()
                ).await()

                val result = startCallInternal(activity, forWhat, callEncryptResult, token, chatRoomName)
                if (!result) {
                    withContext(Dispatchers.Main) { CallWaitDialogUtil.dismiss() }
                }
                onComplete(result, null)
            } catch (e: Exception) {
                L.e { "[Call] startCall failed: ${e.message}" }
                withContext(Dispatchers.Main) { CallWaitDialogUtil.dismiss() }
                onComplete(false, ResUtils.getString(R.string.call_start_failed_tip))
            }
        }
    }

    override fun handleCallMessage(message: SignalServiceDataClass) {
        callMessageHandler.handleCallMessage(message)
    }


    /**
     * 处理通话结束通知消息
     */
    override fun handleCallEndNotification(roomId: String) {
        if (roomId.isEmpty()) return

        L.i { "[Call] handleCallEndNotification, params roomId:$roomId" }

        callMessageHandler.handleCallEndNotification(roomId)
    }


    private fun resolveCallType(forWhat: For): CallType = when (forWhat) {
        is For.Group -> CallType.GROUP
        is For.Account -> CallType.ONE_ON_ONE
        else -> CallType.INSTANT
    }


    private suspend fun startCallInternal(
        activity: Activity,
        forWhat: For,
        callEncryptResult: CallEncryptResult,
        token: String,
        chatRoomName: String?
    ): Boolean {
        val callType = resolveCallType(forWhat)

        val collapseId = MD5Utils.md5AndHexStr(
            System.currentTimeMillis().toString() + mySelfId + DEFAULT_DEVICE_ID
        )
        val notification = Notification(Args(collapseId), LCallConstants.CALL_NOTIFICATION_TYPE)

        val body = StartCallRequestBody(
            callType.type,
            LCallConstants.CALL_VERSION,
            System.currentTimeMillis(),
            conversation = forWhat.id,
            cipherMessages = callEncryptResult.cipherMessages,
            encInfos = callEncryptResult.encInfos,
            notification = notification,
            publicKey = callEncryptResult.publicKey
        )

        val startCallParams = createStartCallParams(body)
        val callIntentBuilder = CallIntent.Builder(application, LCallActivity::class.java)
            .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .withAction(CallIntent.Action.START_CALL)
            .withRoomName(chatRoomName)
            .withCallType(callType.type)
            .withCallRole(CallRole.CALLER.type)
            .withCallerId(mySelfId)
            .withConversationId(forWhat.id)
            .withStartCallParams(startCallParams)
            .withAppToken(token)
            .withNeedAppLock(false)
            .withCallWaitDialogShown(true)

        val cachedUrls = LCallEngine.getAvailableServerUrls()
        if (cachedUrls.isNotEmpty()) {
            activity.startActivity(callIntentBuilder.withCallServerUrls(cachedUrls).build())
            return true
        }

        // ✅ 网络请求 + 异常处理
        return try {
            val response = callService.getServiceUrl(token).await()

            if (response.status == RESPONSE_STATUS_SUCCESS && !response.data?.serviceUrls.isNullOrEmpty()) {
                val urls = response.data!!.serviceUrls!!
                activity.startActivity(callIntentBuilder.withCallServerUrls(urls).build())
                true
            } else {
                L.e { "[Call] startCall getCallServerUrl failed, status:${response.status}" }
                false
            }
        } catch (e: Exception) {
            L.e { "[Call] startCall getCallServerUrl failed: ${e.message}" }
            false
        }
    }

}