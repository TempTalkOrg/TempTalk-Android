package com.difft.android.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import autodispose2.autoDispose
import com.difft.android.base.call.Args
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallDataSourceType
import com.difft.android.base.call.CallEncryptResult
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
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
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.chat.group.GroupUtil
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupRepo
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.CompletableSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.util.CallMessageCreator
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import java.util.ArrayList
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
import com.difft.android.network.config.WsTokenManager
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.yield

class LChatToCallControllerImpl @Inject constructor(
    @ChativeHttpClientModule.Call
    private val httpClient: ChativeHttpClient,
    private val callMessageCreator: CallMessageCreator,
    private val messageEncryptor: INewMessageContentEncryptor,
    private val dbRoomStore: DBRoomStore,
    private val wsTokenManager: WsTokenManager
) : LChatToCallController {

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var callToChatController: LCallToChatController

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

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

    private val autoDisposeCompletable = CompletableSubject.create()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)


    override fun startCall(
        activity: Activity,
        forWhat: For,
        chatRoomName: String?,
        onComplete: (Boolean) -> Unit
    ) {
        coroutineScope.launch {
            try {
                LCallManager.showWaitDialog(activity)
                yield() // 让主线程先渲染一帧

                // 确保 Token 有效
                wsTokenManager.refreshTokenIfNeeded()

                withContext(Dispatchers.IO) {
                    dbRoomStore.createRoomIfNotExist(forWhat)
                }

                val mySelfName = LCallManager.getDisplayName(mySelfId)
                val token = SecureSharedPrefsUtil.getToken()

                val callEncryptResult = withContext(Dispatchers.IO) {
                    callMessageCreator.createCallMessage(
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
                    )
                }.await()

                val result = withContext(Dispatchers.IO) {
                    startCallInternal(activity, forWhat, callEncryptResult, token, chatRoomName)
                }

                onComplete(result)
            } catch (e: Exception) {
                L.e { "[Call] startCall failed: ${e.message}" }
                onComplete(false)
            } finally {
                LCallManager.dismissWaitDialog()
            }
        }
    }

    override fun handleCallMessage(message: SignalServiceDataClass) {
        val envelope = message.signalServiceEnvelope
        val content = message.signalServiceContent?.callMessage
        L.d { "[Call] handleCallMessage, envelope.content:${content}" }
        val roomId = envelope.roomId
        L.i { "[Call] handleCallMessage, receive call message envelope.timestamp:${envelope.timestamp} roomId:${roomId}" }

        if(roomId.isNullOrEmpty()){
            L.e { "[Call] handleCallMessage, envelope roomId is Null Or Empty" }
            return
        }

        if(content?.hasCalling()== true){
            L.i { "[Call] handleCallMessage, has calling message envelope.timestamp:${envelope.timestamp}" }
            if(!TextUtils.isEmpty(roomId)){
                L.i { "[Call] handleCallMessage, start checkCall roomId: ${roomId}" }
                callService.checkCall(SecureSharedPrefsUtil.getToken(), roomId)
                    .subscribeOn(Schedulers.io())
                    .autoDispose(autoDisposeCompletable)
                    .subscribe({
                        L.d { "[Call] handleCallMessage, checkCall result: ${it.data}" }
                        if (it.status == 0 && it.data?.userStopped == false) {
                            L.i { "[Call] handleCallMessage, checkCall success" }
                            if (content.hasCalling()) {
                                coroutineScope.launch {
                                    val callerId = content.calling?.caller?: message.senderId
                                    if(envelope.source != callerId ){
                                        L.e { "[Call] handleCallMessage, checkCall source is not callerId" }
                                        return@launch
                                    }
                                    var callType: CallType = CallType.INSTANT
                                    var conversationId: String? = null
                                    var callName = content.calling.roomName?:""

                                    if (content.calling.hasConversationId()) {
                                        if (content.calling.conversationId.hasNumber()) {
                                            // one-one call
                                            callType = CallType.ONE_ON_ONE
                                            val displayName = LCallManager.getDisplayName(callerId)
                                            if(!displayName.isNullOrEmpty()){
                                                callName = displayName
                                            }
                                            conversationId = content.calling.conversationId.number
                                        } else if (content.calling.conversationId.hasGroupId()) {
                                            // group call
                                            conversationId = content.calling.conversationId.groupId.toStringUtf8()
                                            if(!checkUserIsInGroup(mySelfId, conversationId)){
                                                // if callee not in group, instant call
                                                callType = CallType.INSTANT
                                                conversationId = null
                                                val displayName = LCallManager.getDisplayName(callerId)
                                                callName = if(!displayName.isNullOrEmpty()){
                                                    "${displayName}${ApplicationHelper.instance.getString(R.string.call_instant_call_title)}"
                                                } else {
                                                    ApplicationHelper.instance.getString(R.string.call_instant_call_title_default)
                                                }
                                            } else {
                                                // callee in group, group call
                                                callType = CallType.GROUP
                                                val groupInfo = GroupUtil.getSingleGroupInfo(application, conversationId).blockingFirst()
                                                if(groupInfo.isPresent) {
                                                    val groupName = groupInfo.get().name
                                                    if(!groupName.isNullOrEmpty()){
                                                        callName = groupName
                                                    }
                                                }
                                            }
                                        }
                                    }else {
                                        //instant call
                                        callType = CallType.INSTANT
                                        val displayName = LCallManager.getDisplayName(callerId)
                                        callName = if(!displayName.isNullOrEmpty()){
                                            "${displayName}${ApplicationHelper.instance.getString(R.string.call_instant_call_title)}"
                                        }else {
                                            ApplicationHelper.instance.getString(R.string.call_instant_call_title_default)
                                        }
                                    }

                                    // 检查是否需要本地创建显示call文本消息
                                    if(content.calling.createCallMsg){
                                        // 生成call文本消息
                                        val textContent = if(content.calling.controlType == CallActionType.START.type){
                                            if( callType == CallType.GROUP ){ ApplicationHelper.instance.getString(R.string.call_group_send_message, LCallManager.getDisplayName(callerId)) } else ApplicationHelper.instance.getString(R.string.call_1v1_send_message)
                                        }else {
                                            ApplicationHelper.instance.getString(R.string.call_invite_send_message, LCallManager.getDisplayName(callerId))
                                        }
                                        // 根据call消息类型分别处理
                                        when(content.calling.controlType){
                                            // 处理对方start call
                                            CallActionType.START.type -> {
                                                // 处理自己一端的同步call消息
                                                if(envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID){
                                                    // 根据conversationId确定消息会话
                                                    conversationId?.let { otherSideId ->
                                                        val forWhat = when (callType) {
                                                            CallType.GROUP -> For.Group(otherSideId)
                                                            else -> For.Account(otherSideId)
                                                        }
                                                        // 在此会话中生成并显示call文本消息
                                                        callToChatController.sendOrCreateCallTextMessage(CallActionType.JOINED, textContent, envelope.sourceDevice, content.calling.timestamp, envelope.systemShowTimestamp, For.Account(callerId), forWhat ,callType, true)
                                                    } ?: run {
                                                        L.e { "[Call] handleCallMessage conversationId is null" }
                                                    }
                                                } else {
                                                    // 处理对方发来的call消息
                                                    // 根据callType并结合conversationId或callerId确定消息会话
                                                    val forWhat = when (callType) {
                                                        CallType.GROUP -> conversationId?.let { For.Group(conversationId) }
                                                        else -> For.Account(callerId)
                                                    }
                                                    forWhat?.let {
                                                        // 在此会话中生成并显示call文本消息
                                                        callToChatController.sendOrCreateCallTextMessage(CallActionType.JOINED, textContent, envelope.sourceDevice, content.calling.timestamp, envelope.systemShowTimestamp, For.Account(callerId), forWhat ,callType, true)
                                                    }?: run {
                                                        L.e { "[Call] handleCallMessage forWhat is null" }
                                                    }
                                                }
                                            }
                                            // 处理对方invite call
                                            CallActionType.INVITE.type -> {
                                                // 处理自己一端的同步call消息
                                                if(envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID) {
                                                    content.calling.calleesList?.let { inviteeList ->
                                                        inviteeList.forEachIndexed { index, invitee ->
                                                            // 根据invitee在列表中的位置计算callMessageTime
                                                            val callMessageTime = content.calling.timestamp + index
                                                            callToChatController.sendOrCreateCallTextMessage(CallActionType.JOINED, textContent, envelope.sourceDevice, callMessageTime, envelope.systemShowTimestamp, For.Account(callerId), For.Account(invitee), callType, true)
                                                        }
                                                    }
                                                }else{
                                                    content.calling.calleesList?.let { inviteeList ->
                                                        val index = inviteeList.indexOf(mySelfId)
                                                        // 根据myUid在列表中的位置计算callMessageTime
                                                        val callMessageTime = content.calling.timestamp + index
                                                        callToChatController.sendOrCreateCallTextMessage(CallActionType.JOINED, textContent, envelope.sourceDevice, callMessageTime, envelope.systemShowTimestamp, For.Account(callerId), For.Account(callerId), callType, true)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    val existingCall = LCallManager.getCallListData()?.get(roomId)

                                    if(existingCall == null || (existingCall.source != CallDataSourceType.MESSAGE && roomId != LCallActivity.getCurrentRoomId())){
                                        val callData = CallData(
                                            type = callType.type,
                                            version = 0,
                                            createdAt = it.data?.createdAt?:System.currentTimeMillis(),
                                            roomId = roomId,
                                            caller = CallDataCaller(callerId, envelope.sourceDevice),
                                            conversation = if(callType == CallType.GROUP) conversationId else if(callType == CallType.ONE_ON_ONE) if(envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID) conversationId else callerId else null,
                                            encMeta = null,
                                            callName = callName,
                                            source = CallDataSourceType.MESSAGE
                                        )
                                        L.d { "[Call] handleCallMessage, Calling addCallData:$callData" }
                                        LCallManager.addCallData(callData)

                                        it.data?.anotherDeviceJoined?.let { anotherDeviceJoined ->
                                            if(checkIfShowIncomingCall(anotherDeviceJoined, message.senderId, callData)){
                                                showIncomingNotificationOrActivity(callData)
                                            }
                                        }
                                    } else if(content.calling.controlType == CallActionType.INVITE.type) {
                                        existingCall?.let { callData ->
                                            showIncomingNotificationOrActivity(callData)
                                        }
                                    }
                                }
                            }
                        } else {
                            L.e { "[Call] handleCallMessage, checkCall fail:${it.reason}" }
                        }
                    }, {
                        L.e { "[Call] handleCallMessage, checkCall fail:${it.stackTraceToString()}" }
                    })
            }else {
                L.e { "[Call] handleCallMessage, roomId is empty" }
            }
        }

        else if (content?.hasJoined()== true) {
            L.i { "[Call] handleCallMessage, hasJoined Message:${content.joined.roomId}" }
            content.joined?.let { joined ->
                joined.roomId?.let { roomId ->
                    // 处理会议邀请：本地被叫，自己另一端被叫加入会议时发出的joined消息
                    callToChatController.cancelNotificationById(roomId.hashCode()) // 检查并取消入会通知
                    if(LIncomingCallActivity.isActivityShowing()){
                        val controlMessage = LCallManager.ControlMessage(
                            actionType = CallActionType.JOINED,
                            roomId = roomId
                        )
                        LCallManager.updateControlMessage(controlMessage)
                    }else{
                        LCallManager.stopIncomingCallService(roomId, tag = "joined: other device joined the call")
                    }

                    LCallManager.getCallListData()?.let { callingData ->
                        callingData[roomId]?.hasAnotherDeviceJoined = true
                        LCallManager.updateCallingListData(callingData)
                    }
                }
            }
        }

        else if(content?.hasCancel() == true){
            L.i { "[Call] handleCallMessage, hasCancel Message:${content.cancel.roomId}" }
            content.cancel?.let { cancel ->
                cancel.roomId?.let { roomId ->
                    // 处理会议邀请：本地被叫，主叫取消会议时发出的cancel消息
                    callToChatController.cancelNotificationById(roomId.hashCode()) // 检查并取消入会通知
                    if(LIncomingCallActivity.isActivityShowing()){
                        val controlMessage = LCallManager.ControlMessage(
                            actionType = CallActionType.CANCEL,
                            roomId = roomId
                        )
                        LCallManager.updateControlMessage(controlMessage)
                    }else{
                        LCallManager.stopIncomingCallService(roomId, tag = "cancel: caller cancel the call")
                    }
                }
            }
        }

        else if (content?.hasReject() == true) {
            L.i { "[Call] handleCallMessage, hasRejected Message:${content.reject.roomId}" }
            //处理call入会拒绝通知
            //关闭当前CALL页面
            content.reject?.let { reject ->
                reject.roomId?.let { roomId ->
                    if(!TextUtils.isEmpty(roomId)){
                        // 如果是自己另一端reject会，而自己本地已经入会，则本地忽略自己另一端的reject
                        callToChatController.cancelNotificationById(roomId.hashCode()) // 检查并取消入会通知
                        if(message.senderId == mySelfId && LCallActivity.isInCalling() && LCallActivity.getCurrentRoomId() == roomId){
                            L.i { "[Call] handleCallMessage reject, remote myself device reject, but local has in meeting." }
                            return
                        }else{
                            // 移除callData缓存数据，如果是群call则不移除
                            val callDataList = LCallManager.getCallListData()
                            if(callDataList?.containsKey(roomId) == true){
                                callDataList[roomId]?.let {
                                    if(it.type == CallType.ONE_ON_ONE.type){
                                        LCallManager.removeCallData(roomId)
                                    }
                                }
                            }
                            // 处理会议呼叫：本地主叫，被叫拒绝入会时发送的reject消息
                            if(LCallActivity.isInCalling() && LCallActivity.getCurrentRoomId() == reject.roomId){
                                val controlMessage = LCallManager.ControlMessage(
                                    actionType = CallActionType.REJECT,
                                    roomId = roomId
                                )
                                LCallManager.updateControlMessage(controlMessage)
                            }

                            // 处理会议邀请：本地被叫，自己另一端设备拒绝入会时发送的reject消息
                            if(LIncomingCallActivity.isActivityShowing()){
                                val controlMessage = LCallManager.ControlMessage(
                                    actionType = CallActionType.REJECT,
                                    roomId = roomId
                                )
                                LCallManager.updateControlMessage(controlMessage)
                            }else {
                                LCallManager.stopIncomingCallService(roomId, tag = "reject: your other device reject the call")
                            }
                        }
                    }
                }
            }
        }

        else if (content?.hasHangup() == true) {
            L.i { "[Call] handleCallMessage, hasHangup Message:${content.hangup.roomId}" }
            content.hangup?.let { hangup ->
                hangup.roomId?.let { roomId ->
                    if(LCallActivity.isInCalling() && LCallActivity.getCurrentRoomId() == roomId){
                        val controlMessage = LCallManager.ControlMessage(
                            actionType = CallActionType.HANGUP,
                            roomId = roomId
                        )
                        LCallManager.updateControlMessage(controlMessage)
                    }
                }
                LCallManager.removeCallData(roomId)
            }
        }
    }


    override fun handleCallEndNotification(roomId: String) {
        if(roomId.isNotEmpty()){
            L.i { "[Call] handleCallEndNotification, params roomId:$roomId" }
            LCallManager.removeCallData(roomId)
            callToChatController.cancelNotificationById(roomId.hashCode())
            if(LIncomingCallActivity.isActivityShowing()){
                val controlMessage = LCallManager.ControlMessage(
                    actionType = CallActionType.CALLEND,
                    roomId = roomId
                )
                LCallManager.updateControlMessage(controlMessage)
            }else{
                LCallManager.stopIncomingCallService(roomId, tag = "ended: server call end notification")
            }
            if(LCallActivity.isInCalling() && LCallActivity.getCurrentRoomId() == roomId){
                val controlMessage = LCallManager.ControlMessage(
                    actionType = CallActionType.CALLEND,
                    roomId = roomId
                )
                LCallManager.updateControlMessage(controlMessage)
            }
        }
    }

    override fun inviteCall(context: Context, roomId: String, roomName: String?, callType: String?, mKey: ByteArray?, inviteMembers: ArrayList<String>, conversationId: String?){

        if(roomId.isEmpty() || inviteMembers.isEmpty()){
            ToastUtil.show("roomId or invite members is empty")
            return
        }
        L.d { "[Call] inviteCall, params roomId:$roomId, roomName:$roomName callType:$callType" }
        val type = if (!callType.isNullOrEmpty()) CallType.fromString(callType)?:CallType.INSTANT else CallType.INSTANT

        val createdAt = System.currentTimeMillis()

        callMessageCreator.createCallMessage(
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
        ).concatMap { callEncryptResult ->
            val collapseId = MD5Utils.md5AndHexStr(System.currentTimeMillis().toString() + mySelfId + DEFAULT_DEVICE_ID)
            val notification = Notification(Args(collapseId), 22)
            val body = InviteCallRequestBody(
                roomId = roomId,
                timestamp = System.currentTimeMillis(),
                cipherMessages = callEncryptResult.cipherMessages,
                encInfos = callEncryptResult.encInfos,
                notification = notification,
                publicKey = callEncryptResult.publicKey
            )
            callService.inviteCall(SecureSharedPrefsUtil.getToken(), body)
        }
            .compose(RxUtil.getSingleSchedulerComposer())
            .autoDispose(autoDisposeCompletable)
            .subscribe({
                when(it.status){
                    0 -> {
                        L.d { "[Call] inviteCall, invite success, response: reason:${it.reason}, data:${it.data}" }
                        // 根据配置发送或本地生成call文本消息
                        coroutineScope.launch {
                            val textContent =  ApplicationHelper.instance.getString(R.string.call_invite_send_message, LCallManager.getDisplayName(mySelfId))
                            inviteMembers.forEach { uid ->
                                val forWhat = For.Account(uid)
                                LCallManager.sendOrLocalCallTextMessage(CallActionType.INVITE, textContent, DEFAULT_DEVICE_ID, createdAt, it.data?.systemShowTimestamp ?: createdAt , For.Account(mySelfId), forWhat, type, callConfig.createCallMsg, inviteMembers)
                            }
                        }
                    }

                    2 -> {
                        L.e { "[Call] inviteCall, invite failed, response: status:${it.status}, reason:${it.reason}, data:${it.data}" }
                        it.data?.invalidUids?.let { invalidUids ->
                            coroutineScope.launch{
                                val inviteFailedNames = invalidUids.map { uid ->
                                    LCallManager.getDisplayNameById(uid)
                                }.joinToString(",")

                                withContext(Dispatchers.Main){
                                    ToastUtil.show("Invite failed for: $inviteFailedNames")
                                }
                            }
                        } ?: ToastUtil.show(R.string.call_invite_fail_tip)
                    }

                    11001 -> {
                        L.e { "[Call] inviteCall, invite failed, response: status:${it.status}, reason:${it.reason}, data:${it.data}" }
                        it.data?.stale?.let { stale ->
                            coroutineScope.launch {
                                val inviteFailedNames = stale.map { staleData ->
                                    LCallManager.getDisplayNameById(staleData.uid)
                                }.joinToString(",")
                                withContext(Dispatchers.Main){
                                    ToastUtil.show("Invite failed for: $inviteFailedNames")
                                }
                            }
                        } ?: ToastUtil.show(R.string.call_invite_fail_tip)
                    }

                    else -> {
                        L.e { "[Call] inviteCall, invite failed, response: status:${it.status}, reason:${it.reason}, data:${it.data}" }
                        ToastUtil.show(R.string.call_invite_fail_tip)
                    }
                }
            }, {
                it.printStackTrace()
                L.e { "[Call] inviteCall, request fail, error:${it.stackTraceToString()}" }
                it.message?.let { message -> ToastUtil.show(message) }
            })
    }


    private fun checkUserIsInGroup(userId: String, gid: String): Boolean {
        var result = false
        try{
            val groupData = groupRepo.getGroupInfo(gid).blockingGet().data
            val groupMembersSet = mutableSetOf<String>()
            groupData?.members?.forEach {
                groupMembersSet.add(it.uid)
            }
            if(groupMembersSet.contains(userId)){
                result = true
            }
        }catch (e: Exception){
            L.e { "[Call] LChatToCallControllerImpl checkUserIsInGroup, error:${e.stackTraceToString()}" }
            e.printStackTrace()
        }
        return result
    }

    private fun checkIfShowIncomingCall(anotherDeviceJoined: Boolean, msgSenderId: String, callData: CallData): Boolean {
        // 处理会议邀请：自己另一端没有入会、且call消息发起人不是自己、且不是当前正在进行中的会议
        return !anotherDeviceJoined &&
                msgSenderId != mySelfId &&
                !callToChatController.isIncomingCallNotifying(callData.roomId) &&
                LCallActivity.getCurrentRoomId() != callData.roomId
    }

    private suspend fun showIncomingNotificationOrActivity(callData: CallData) = withContext(Dispatchers.Main) {
        val intentNotify = Intent().apply {
            putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, callData.type)
            putExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE, CallRole.CALLEE.type)
            putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, callData.roomId)
            putExtra(LCallConstants.BUNDLE_KEY_CALL_NAME, callData.callName)
            putExtra(LCallConstants.BUNDLE_KEY_CALLER_ID, callData.caller.uid)
            putExtra(LCallConstants.BUNDLE_KEY_CONVERSATION_ID, callData.conversation)
        }
        LCallManager.startIncomingCallService(intentNotify)
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

        val startCallParams = LCallManager.createStartCallParams(body)
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

        val cachedUrls = LCallEngine.getAvailableServerUrls()
        if (cachedUrls.isNotEmpty()) {
            activity.startActivity(callIntentBuilder.withCallServerUrls(cachedUrls).build())
            return true
        }

        // ✅ 网络请求 + 异常处理
        return try {
            val response = callService.getServiceUrl(token)
                .compose(RxUtil.getSingleSchedulerComposer())
                .await() // 使用扩展 await() 挂起转换

            if (response.status == 0 && !response.data?.serviceUrls.isNullOrEmpty()) {
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