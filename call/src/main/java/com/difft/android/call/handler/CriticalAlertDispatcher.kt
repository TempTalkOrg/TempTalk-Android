package com.difft.android.call.handler

import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.core.CallUiController
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.requests.CriticalAlertDestination
import com.difft.android.network.requests.CriticalAlertGroup
import com.difft.android.network.requests.CriticalAlertRequestBodyNew
import com.difft.android.network.responses.CriticalAlertResponse
import difft.android.messageserialization.For
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Owns the critical alert (server-side "重要通知") workflow that was previously
 * inlined in `LCallViewModel.handleCriticalAlertNew` and
 * `checkCriticalAlertStatusById`. The dispatcher builds the request, parses
 * the response, triggers barrage / toast feedback and persists delivery
 * receipts via `callToChatController`.
 */
class CriticalAlertDispatcher(
    private val scope: CoroutineScope,
    private val httpClientProvider: () -> ChativeHttpClient,
    private val callToChatController: LCallToChatController,
    private val participantManager: ParticipantManager,
    private val callUiController: CallUiController,
    private val room: Room,
    private val roomIdGetter: () -> String?,
    private val showBarrage: (identity: io.livekit.android.room.participant.Participant, message: String) -> Unit,
    private val showToast: (message: String) -> Unit,
) {

    fun send(gid: String? = null, callback: ((Boolean) -> Unit)? = null) {
        scope.launch {
            val chatHttpClient = httpClientProvider()
            val auth = SecureSharedPrefsUtil.getBasicAuth()
            var baseTimestamp = System.currentTimeMillis()

            val awaitingJoinInvitees = participantManager.awaitingJoinInvitees.value
            val destinations = if (awaitingJoinInvitees.isEmpty()) emptyList() else {
                awaitingJoinInvitees.map {
                    baseTimestamp += 1
                    CriticalAlertDestination(number = it, timestamp = baseTimestamp)
                }
            }

            val criticalAlertGroup = if (gid.isNullOrEmpty()) null else CriticalAlertGroup(
                gid = gid,
                timestamp = baseTimestamp + 1,
            )

            val requestBody = CriticalAlertRequestBodyNew(
                destinations = destinations,
                group = criticalAlertGroup,
                roomId = roomIdGetter().orEmpty(),
            )

            try {
                val response = withContext(Dispatchers.IO) {
                    chatHttpClient.httpService.sendCriticalAlertNew(auth, requestBody)
                }
                if (response.status == 0) {
                    callback?.invoke(true)
                    showBarrage(
                        room.localParticipant,
                        getString(R.string.call_barrage_message_critical_alert_success),
                    )
                    response.serverTimestamp?.let { serverTimestamp ->
                        L.i { "[Call] sendCriticalAlert response serverTimestamp:$serverTimestamp" }
                        persistDeliveries(requestBody, response, serverTimestamp, gid)
                    }
                } else {
                    L.e { "[Call] handleCriticalAlert failed, status = ${response.status} reason = ${response.reason}" }
                    callback?.invoke(false)
                    showToast(getString(R.string.call_barrage_message_critical_alert_failed))
                }
            } catch (e: Exception) {
                L.w { "[LCallViewModel] handleCriticalAlertNew error: ${e.stackTraceToString()}" }
                val reason = e.message
                val code = (e as? HttpException)?.code()
                callback?.invoke(false)
                when (code) {
                    413 -> {
                        L.w { "[Call] Critical alert limited - status: $code reason: $reason" }
                        showToast(getString(R.string.call_barrage_message_critical_alert_limited))
                    }
                    else -> {
                        L.e { "[Call] Critical alert failed - status: $code, reason: $reason" }
                        showToast(getString(R.string.call_barrage_message_critical_alert_failed))
                    }
                }
            }
        }
    }

    private suspend fun persistDeliveries(
        requestBody: CriticalAlertRequestBodyNew,
        response: BaseResponse<CriticalAlertResponse>,
        serverTimestamp: Long,
        gid: String?,
    ) {
        val myUid = callToChatController.getMySelfUid()
        val fromWho = For.Account(myUid)
        response.data?.delivers?.forEach { deliver ->
            val forWhat = For.Account(deliver)
            requestBody.destinations?.find { dest -> dest.number == deliver }?.timestamp?.let { timestamp ->
                callToChatController.createCriticalAlertMessage(serverTimestamp, timestamp, fromWho, forWhat, DEFAULT_DEVICE_ID)
            }
        }
        gid?.let {
            val forWhat = For.Group(gid)
            requestBody.group?.timestamp?.let { timestamp ->
                callToChatController.createCriticalAlertMessage(serverTimestamp, timestamp, fromWho, forWhat, DEFAULT_DEVICE_ID)
            }
        }
    }

    /** Refreshes the group's critical-alert flag and updates the UI controller. */
    fun refreshGroupStatus(callIntent: CallIntent) {
        if (callIntent.callType != CallType.GROUP.type) return
        val conversationId = callIntent.conversationId ?: return
        scope.launch(Dispatchers.IO) {
            val group = callToChatController.getSingleGroupInfo(conversationId)
            val status = group?.criticalAlert ?: false
            withContext(Dispatchers.Main) {
                callUiController.setCriticalAlertEnable(status)
            }
        }
    }
}
