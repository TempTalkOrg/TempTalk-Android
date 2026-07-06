package com.difft.android.call.manager

import com.difft.android.base.utils.globalServices

import com.difft.android.base.call.LogPayLoad
import com.difft.android.base.call.StatisticsLogRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.connect.ConnectionAttempt
import com.difft.android.call.connect.MeetingConnectionPlanner
import com.difft.android.call.data.CallStatisticsEvent
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallStatisticsLogManager @Inject constructor(
    private val onGoingCallStateManager: OnGoingCallStateManager,
    @ChativeHttpClientModule.Call private val callHttpClient: dagger.Lazy<ChativeHttpClient>,
) {

    private val callService by lazy {
        callHttpClient.get().getService(LCallHttpService::class.java)
    }

    private val queue = mutableListOf<LogPayLoad>()
    private val lock = Any()
    private var delayedFlushJob: Job? = null

    @Volatile
    private var currentRoomLocalId: String? = null

    @Volatile
    private var currentRoomId: String? = null

    fun setRoomLocalId(localId: String?) {
        currentRoomLocalId = localId
    }

    fun setRoomId(roomId: String?) {
        currentRoomId = roomId
        if (roomId != null) {
            backfillRoomId(roomId)
        }
    }

    private fun backfillRoomId(roomId: String) {
        synchronized(lock) {
            for (i in queue.indices) {
                val p = queue[i]
                if (p.roomId.isNullOrEmpty() && p.event in BACKFILL_EVENTS) {
                    queue[i] = p.copy(roomId = roomId)
                }
            }
        }
    }

    fun report(event: CallStatisticsEvent) {
        val roomId = currentRoomId
            ?: onGoingCallStateManager.getCurrentRoomId()
            ?: ""
        val payload = LogPayLoad(
            roomId = roomId,
            uuid = currentRoomLocalId,
            event = event.eventName,
            details = event.toDetails(),
        )
        val (shouldFlush, shouldScheduleDelay) = synchronized(lock) {
            queue.add(payload)
            val flush = queue.size >= FLUSH_THRESHOLD
            val scheduleDelay = !flush && queue.size == 1
            flush to scheduleDelay
        }
        if (shouldFlush) {
            cancelDelayedFlush()
            flushAll()
        } else if (shouldScheduleDelay) {
            scheduleDelayedFlush()
        }
    }

    private fun scheduleDelayedFlush() {
        delayedFlushJob?.cancel()
        delayedFlushJob = appScope.launch {
            delay(FLUSH_DELAY_MS)
            flushAll()
        }
    }

    private fun cancelDelayedFlush() {
        delayedFlushJob?.cancel()
        delayedFlushJob = null
    }

    /**
     * Drains the queue and sends all pending events. Call on call-end to avoid data loss.
     */
    fun flushAll() {
        cancelDelayedFlush()
        val batch = synchronized(lock) {
            if (queue.isEmpty()) return
            ArrayList(queue).also { queue.clear() }
        }
        appScope.launch(Dispatchers.IO) {
            val token = (globalServices.userManager.getUserData()?.microToken ?: "")
            if (token.isNullOrEmpty()) {
                L.e { "[Call] CallStatisticsLogManager flush skipped: no token" }
                return@launch
            }
            for ((index, payload) in batch.withIndex()) {
                if (index > 0) delay(SEND_INTERVAL_MS)
                sendSingle(token, payload)
            }
        }
    }

    private suspend fun sendSingle(token: String, payload: LogPayLoad) {
        try {
            val response = callService.statisticsLog(token, StatisticsLogRequestBody(payload))
            if (response.status == 0) {
                L.i { "[Call] CallStatisticsLogManager sent event=${payload.event}" }
            } else {
                L.e { "[Call] CallStatisticsLogManager event=${payload.event} status=${response.status}" }
            }
        } catch (e: Exception) {
            L.e { "[Call] CallStatisticsLogManager event=${payload.event} error=${e.stackTraceToString()}" }
        }
    }

    fun reportConnectFail(connectUrl: String, serverHost: String, useQuic: Boolean, nodeType: String, errorMsg: String) {
        val host = runCatching { connectUrl.toUri().host }.getOrNull().orEmpty()
        val ip = if (MeetingConnectionPlanner.isIpHost(host)) host else ""
        report(
            CallStatisticsEvent.ConnectFail(
                mode = if (useQuic) "quic" else "wss",
                nodeType = nodeType,
                ip = ip,
                domain = serverHost,
                errorMsg = errorMsg,
            )
        )
    }

    fun reportChannelDowngradeIfNeeded(
        connectUrl: String,
        serverHost: String,
        useQuic: Boolean,
        nodeType: String,
        hadQuicFailure: Boolean,
        hadPrimaryFailure: Boolean,
        lastFailedErrorMsg: String,
    ) {
        val quicDowngradedToWss = hadQuicFailure && !useQuic
        val primaryDowngradedToFallback = hadPrimaryFailure && nodeType == ConnectionAttempt.NODE_TYPE_FALLBACK
        if (!quicDowngradedToWss && !primaryDowngradedToFallback) return
        val host = runCatching { connectUrl.toUri().host }.getOrNull().orEmpty()
        val ip = if (MeetingConnectionPlanner.isIpHost(host)) host else ""
        report(
            CallStatisticsEvent.ChannelDowngrade(
                mode = if (useQuic) "quic" else "wss",
                nodeType = nodeType,
                ip = ip,
                domain = serverHost,
                errorMsg = lastFailedErrorMsg,
            )
        )
    }

    private companion object {
        const val FLUSH_THRESHOLD = 5
        const val FLUSH_DELAY_MS = 60_000L
        const val SEND_INTERVAL_MS = 2_000L
        val BACKFILL_EVENTS = setOf(
            CallStatisticsEvent.ChannelDowngrade.EVENT_NAME,
            CallStatisticsEvent.RoomReconnectFail.EVENT_NAME,
        )
    }
}
