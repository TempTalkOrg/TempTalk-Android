package com.difft.android.call.manager


import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.utils.appScope
import com.difft.android.call.LCallToChatController
import com.difft.android.network.HttpService
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallMessageManager @Inject constructor(
    private val callToChatController: LCallToChatController,
    @ChativeHttpClientModule.Chat private val chatHttpClient: dagger.Lazy<ChativeHttpClient>,
    private val messagingGate: MessagingGate,
) {

    private val chatHttpService by lazy {
        chatHttpClient.get().getService(HttpService::class.java)
    }

    fun sendOrLocalCallTextMessage(
        callActionType: CallActionType,
        textContent: String,
        sourceDevice: Int,
        timestamp: Long,
        systemShowTime: Long,
        fromWho: For,
        forWhat: For,
        callType: CallType,
        createCallMsg: Boolean,
        inviteeList: List<String> = emptyList()
    ) {
        callToChatController.sendOrCreateCallTextMessage(
            callActionType,
            textContent,
            sourceDevice,
            timestamp,
            systemShowTime,
            fromWho,
            forWhat,
            callType,
            createCallMsg,
            inviteeList
        )
    }

    fun removePendingMessage(source: String, timestamp: String) {
        appScope.launch(Dispatchers.IO) {
            
            
            val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "call-delete")
            if (admission == null) {
                L.i { "[MigrationPause] event=receive_dropped source=call-delete count=1" }
                return@launch
            }
            admission.use { deletePendingMessage(source, timestamp) }
        }
    }

    private suspend fun deletePendingMessage(source: String, timestamp: String) {
        try {
            val result = chatHttpService.removePendingMessage(
                source,
                timestamp
            )

            if (result.isSuccess()) {
                L.i { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp success" }
            } else {
                L.e { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp failed -> ${result.reason}" }
            }
        } catch (e: Exception) {
            L.e { "[Call] CallMessageManager removePendingMessage remove pending message $timestamp failed -> ${e.message}" }
        }
    }

    fun getContactsUpdateListener(): Flow<List<String>> {
        return callToChatController.getContactsUpdateListener()
    }
}

