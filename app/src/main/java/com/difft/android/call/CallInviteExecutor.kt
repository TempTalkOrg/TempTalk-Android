package com.difft.android.call

import com.difft.android.base.utils.globalServices

import com.difft.android.base.call.Args
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallEncryptOutcome
import com.difft.android.base.call.CallEncryptResult
import com.difft.android.base.call.CallType
import com.difft.android.base.call.InviteCallRequestBody
import com.difft.android.base.call.Notification
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.MD5Utils
import com.difft.android.base.utils.appScope
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.handler.InviteRequestState
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.network.BaseResponse
import com.difft.android.websocket.api.util.CallMessageCreator
import difft.android.messageserialization.For
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList

/**
 * Encapsulates the invite-call workflow: parameter validation, request
 * construction, API call, and response/error handling.
 */
internal class CallInviteExecutor(
    private val callService: LCallHttpService,
    private val callMessageCreator: CallMessageCreator,
    private val callConfig: CallConfig,
    private val mySelfId: String,
    private val contactorCacheManager: ContactorCacheManager,
) {

    companion object {
        private const val INVITE_NOTIFICATION_TYPE = 22
        private const val RESPONSE_STATUS_SUCCESS = 0
        private const val RESPONSE_STATUS_INVALID_UIDS = 2
        private const val RESPONSE_STATUS_STALE = 11001
    }

    suspend fun inviteCall(
        roomId: String,
        roomName: String?,
        callType: String?,
        mKey: ByteArray?,
        inviteMembers: ArrayList<String>,
        conversationId: String?,
    ): InviteRequestState {
        if (roomId.isEmpty() || inviteMembers.isEmpty()) {
            ToastUtil.show("roomId or invite members is empty")
            return InviteRequestState.FAILED
        }

        L.d { "[Call] inviteCall, params roomId:$roomId, roomName:$roomName callType:$callType" }

        val type = resolveCallType(callType)
        val createdAt = System.currentTimeMillis()

        return withContext(Dispatchers.IO) {
            try {
                val callEncryptResult = when (val outcome = callMessageCreator.createCallMessage(
                    null, type, null, CallActionType.INVITE,
                    conversationId = if (type == CallType.INSTANT) null else conversationId,
                    inviteMembers, listOf(roomId), roomName, mySelfId, mKey,
                    createCallMsg = callConfig.createCallMsg,
                    createdAt = createdAt,
                )) {
                    is CallEncryptOutcome.Success -> outcome.result
                    is CallEncryptOutcome.Failed -> {
                        L.e { "[Call] inviteCall encryption failed: ${outcome.reason}" }
                        return@withContext InviteRequestState.FAILED
                    }
                }
                val requestBody = buildInviteRequest(roomId, callEncryptResult)
                val response = callService.inviteCall((globalServices.userManager.getUserData()?.microToken ?: ""), requestBody)
                handleResponse(response, inviteMembers, type, createdAt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                L.e { "[Call] inviteCall, request fail, error:${error.stackTraceToString()}" }
                error.message?.let { ToastUtil.show(it) }
                InviteRequestState.FAILED
            }
        }
    }

    private fun resolveCallType(callType: String?): CallType =
        if (!callType.isNullOrEmpty()) CallType.fromString(callType) ?: CallType.INSTANT else CallType.INSTANT

    private fun buildInviteRequest(roomId: String, encryptResult: CallEncryptResult): InviteCallRequestBody {
        val collapseId = MD5Utils.md5AndHexStr(
            System.currentTimeMillis().toString() + mySelfId + DEFAULT_DEVICE_ID
        )
        return InviteCallRequestBody(
            roomId = roomId,
            timestamp = System.currentTimeMillis(),
            cipherMessages = encryptResult.cipherMessages,
            encInfos = encryptResult.encInfos,
            notification = Notification(Args(collapseId), INVITE_NOTIFICATION_TYPE),
            publicKey = encryptResult.publicKey,
        )
    }

    private fun handleResponse(
        response: BaseResponse<com.difft.android.base.call.InviteCallResponseData>,
        inviteMembers: ArrayList<String>,
        callType: CallType,
        createdAt: Long,
    ): InviteRequestState = when (response.status) {
        RESPONSE_STATUS_SUCCESS -> {
            L.d { "[Call] inviteCall, invite success, response: reason:${response.reason}, data:${response.data}" }
            onSuccess(response.data, inviteMembers, callType, createdAt)
            InviteRequestState.SUCCESS
        }
        RESPONSE_STATUS_INVALID_UIDS -> {
            L.e { "[Call] inviteCall, invite failed, response: status:${response.status}, reason:${response.reason}, data:${response.data}" }
            onFailure(response.data?.invalidUids, null)
            InviteRequestState.FAILED
        }
        RESPONSE_STATUS_STALE -> {
            L.e { "[Call] inviteCall, invite failed, response: status:${response.status}, reason:${response.reason}, data:${response.data}" }
            onFailure(null, response.data?.stale)
            InviteRequestState.FAILED
        }
        else -> {
            L.e { "[Call] inviteCall, invite failed, response: status:${response.status}, reason:${response.reason}, data:${response.data}" }
            ToastUtil.show(R.string.call_invite_fail_tip)
            InviteRequestState.FAILED
        }
    }

    private fun onSuccess(
        responseData: com.difft.android.base.call.InviteCallResponseData?,
        inviteMembers: ArrayList<String>,
        callType: CallType,
        createdAt: Long,
    ) {
        appScope.launch {
            val textContent = ApplicationHelper.instance.getString(
                R.string.call_invite_send_message,
                contactorCacheManager.getDisplayName(mySelfId),
            )
            val systemShowTimestamp = responseData?.systemShowTimestamp ?: createdAt

            inviteMembers.forEach { uid ->
                LCallManager.sendOrLocalCallTextMessage(
                    CallActionType.INVITE, textContent, DEFAULT_DEVICE_ID,
                    createdAt, systemShowTimestamp,
                    For.Account(mySelfId), For.Account(uid), callType,
                    callConfig.createCallMsg, inviteMembers,
                )
            }
        }
    }

    private fun onFailure(
        invalidUids: List<String>?,
        stale: List<com.difft.android.base.call.Stale>?,
    ) {
        when {
            invalidUids != null -> showFailedNames(invalidUids) { contactorCacheManager.getDisplayNameById(it) }
            stale != null -> showFailedNames(stale.mapNotNull { it.uid }) { contactorCacheManager.getDisplayNameById(it) }
            else -> ToastUtil.show(R.string.call_invite_fail_tip)
        }
    }

    private fun showFailedNames(uids: List<String>, nameResolver: suspend (String) -> String?) {
        appScope.launch {
            val names = uids.map { nameResolver(it) }.joinToString(",")
            withContext(Dispatchers.Main) { ToastUtil.show("Invite failed for: $names") }
        }
    }
}
