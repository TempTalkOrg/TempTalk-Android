package com.difft.android.call.connect

import android.content.Context
import android.os.SystemClock
import androidx.core.net.toUri
import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.network.CertValidationFailureDetector
import com.difft.android.base.network.NetworkRiskNotifier
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.call.LCallConstants
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallEngine
import com.difft.android.call.LCallManager
import com.difft.android.call.R
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.core.CallTlsProvider
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.CallStatus

import com.difft.android.call.data.ServerNode
import com.difft.android.base.call.StartCallRequestBody
import com.difft.android.call.data.createStartCallParams
import com.difft.android.call.exception.ServerConnectionException
import com.difft.android.call.exception.StartCallException
import com.difft.android.call.manager.CallStatisticsLogManager
import io.livekit.android.room.RoomException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile



internal class CallConnectionCoordinator(
    private val appContext: Context,
    private val roomCtl: CallRoomController,
    private val callTlsProvider: CallTlsProvider,
    private val statisticsLogManager: CallStatisticsLogManager,
    private val authTokenProvider: AuthTokenProvider,
) {

    




    @Volatile
    var isRetryUrlConnecting: Boolean = false
        private set

    












    @Volatile
    var isManualSwitchReconnecting: Boolean = false
        private set

    




    fun consumeManualSwitchReconnecting(): Boolean {
        val was = isManualSwitchReconnecting
        isManualSwitchReconnecting = false
        return was
    }

    












    private fun embeddedServiceUrlsFallbackOrNull(): ServiceUrls? =
        if (ProxyConfigProvider.isProxyActiveForCall) null
        else DefaultGlobalConfigCallServiceUrlsReader.read(appContext)

    
    private fun remainingBudgetMs(deadlineMs: Long): Long =
        (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    
    private fun budgetExhausted(deadlineMs: Long): Boolean = remainingBudgetMs(deadlineMs) == 0L

    




    private fun reportCertRiskIfNeeded(t: Throwable, serverHost: String) {
        if (CertValidationFailureDetector.isCertValidationFailure(t)) {
            NetworkRiskNotifier.onCertValidationFailed("call:$serverHost")
        }
    }

    







    suspend fun connectToRoomWithFailover(
        callParams: ByteArray?,
        useQuic: Boolean,
    ): Boolean {
        isManualSwitchReconnecting = false
        val certPem = callTlsProvider.trustedCert
        if (callParams == null) {
            failWith(StartCallException(getString(R.string.call_params_startcall_exception_tip)))
            return false
        }
        if (certPem.isEmpty()) {
            L.e { "[Call] CallConnectionCoordinator trusted cert is empty" }
            failWith(StartCallException(getString(R.string.call_params_startcall_exception_tip)))
            return false
        }
        val appToken = authTokenProvider.current()
        if (appToken.isEmpty()) {
            L.e { "[Call] CallConnectionCoordinator app token is null" }
            failWith(StartCallException(getString(R.string.call_params_startcall_exception_tip)))
            return false
        }
        if (roomCtl.isProxyActiveWithoutTurn()) {
            L.w { "[Call] blocked: proxy active without TURN, media would expose client IP" }
            failWith(StartCallException(getString(R.string.call_proxy_turn_required_tip)))
            return false
        }

        var serviceUrls: ServiceUrls? = LCallManager.ensureCallServiceUrlsForCall()
            ?: embeddedServiceUrlsFallbackOrNull()

        if (serviceUrls == null) {
            failWith(StartCallException(getString(R.string.call_params_url_exception_tip)))
            return false
        }

        
        
        
        statisticsLogManager.setRoomLocalId(roomCtl.roomOrNull()?.localId)

        var failureCount = 0
        var hadQuicFailure = false
        var hadPrimaryFailure = false
        var lastFailedErrorMsg = ""
        isRetryUrlConnecting = true
        val deadlineMs = SystemClock.elapsedRealtime() + CONNECT_BUDGET_MS
        for (phase in 0 until 3) {
            if (budgetExhausted(deadlineMs)) break
            if (phase == 1) {
                delay(2_000L)
                LCallManager.fetchCallServiceUrlAndCache()
                serviceUrls = LCallManager.getCachedServiceUrls() ?: serviceUrls
            } else if (phase == 2) {
                delay(5_000L)
                serviceUrls = embeddedServiceUrlsFallbackOrNull() ?: serviceUrls
            }

            val su = serviceUrls ?: break
            val attempts = MeetingConnectionPlanner.buildAttempts(su, useQuic)
            if (attempts.isEmpty()) {
                L.w { "[Call] connectToRoomWithFailover phase=$phase no connection attempts" }
                continue
            }

            for ((idx, att) in attempts.withIndex()) {
                if (failureCount > 0) {
                    
                    
                    
                    val backoffMs = ConnectionBackoff.delayMsBeforeRetryAfterFailure(failureCount)
                    delay(backoffMs.coerceAtMost(remainingBudgetMs(deadlineMs)))
                }
                
                
                
                if (budgetExhausted(deadlineMs)) {
                    L.w {
                        "[Call] meeting connect budget exhausted after ${CONNECT_BUDGET_MS}ms " +
                            "(failures=$failureCount, phase=$phase, lastErr=$lastFailedErrorMsg)"
                    }
                    break
                }
                L.i {
                    "[Call] meeting connect phase=$phase ${idx + 1}/${attempts.size} url=${att.connectUrl} quic=${att.useQuic}"
                }

                var transientErrorMsg: String? = null
                var terminalError: Throwable? = null
                roomCtl.connect(
                    att.serverHost,
                    att.connectUrl,
                    certPem,
                    appToken,
                    callParams,
                    att.useQuic,
                ) { t ->
                    if (t is CancellationException) throw t
                    L.e { "[Call] connect exception url=${att.connectUrl} err=${t}" }
                    when (classifyConnectFailure(t)) {
                        ConnectFailureCategory.TRANSIENT -> {
                            reportCertRiskIfNeeded(t, att.serverHost)
                            LCallEngine.reportConnectionFailure(att.connectUrl)
                            roomCtl.disconnectQuietly()
                            transientErrorMsg = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                        }
                        ConnectFailureCategory.SERVER_VERDICT -> {
                            L.w { "[Call] server verdict status=${serverVerdictStatus(t)} url=${att.connectUrl}" }
                            terminalError = StartCallException(t.message)
                        }
                        ConnectFailureCategory.PRECONDITION -> {
                            
                            
                            
                            terminalError = ServerConnectionException(getString(R.string.call_params_startcall_exception_tip), t)
                        }
                    }
                }

                if (transientErrorMsg == null && terminalError == null) {
                    LCallEngine.setConnectedServerUrl(att.connectUrl)
                    reportChannelDowngradeIfNeeded(att, hadQuicFailure, hadPrimaryFailure, lastFailedErrorMsg)
                    isRetryUrlConnecting = false
                    return true
                }

                
                
                val errorMsg = terminalError?.let { "${it.javaClass.simpleName}: ${(it.cause ?: it).message.orEmpty()}" }
                    ?: transientErrorMsg.orEmpty()
                reportConnectFail(att, errorMsg)

                terminalError?.let { err ->
                    isRetryUrlConnecting = false
                    failWith(err)
                    return false
                }

                if (att.useQuic) hadQuicFailure = true
                if (att.nodeType == ConnectionAttempt.NODE_TYPE_PRIMARY) hadPrimaryFailure = true
                lastFailedErrorMsg = errorMsg

                failureCount++
                if (failureCount == 6) {
                    L.w {
                        "[Call] meeting connect transient failures exceeded 5 (count=$failureCount, phase=$phase, lastUrl=${att.connectUrl})"
                    }
                }
                isRetryUrlConnecting = idx < attempts.lastIndex || phase < 2
                if (failureCount > MAX_TRANSIENT_FAILURES) {
                    isRetryUrlConnecting = false
                    failWith(ServerConnectionException(getString(R.string.call_connect_timeout_tip)))
                    return false
                }
            }
        }

        isRetryUrlConnecting = false
        failWith(ServerConnectionException(getString(R.string.call_connect_timeout_tip)))
        return false
    }

    




    suspend fun connectToRoomManualSwitch(
        serverUrl: String,
        callParams: ByteArray?,
        useQuicSignal: Boolean,
    ): Boolean {
        if (callParams == null) {
            failWith(StartCallException(getString(R.string.call_params_startcall_exception_tip)))
            return false
        }
        val appToken = authTokenProvider.current()
        if (appToken.isEmpty()) {
            failWith(StartCallException(getString(R.string.call_params_startcall_exception_tip)))
            return false
        }
        if (roomCtl.isProxyActiveWithoutTurn()) {
            L.w { "[Call] manualSwitch blocked: proxy active without TURN, media would expose client IP" }
            failWith(StartCallException(getString(R.string.call_proxy_turn_required_tip)))
            return false
        }
        val normalizedUrl = MeetingConnectionPlanner.normalizeConnectUrl(serverUrl) ?: run {
            failWith(StartCallException(getString(R.string.call_params_url_exception_tip)))
            return false
        }
        val connectUrl = enforceWssDomainUrl(normalizedUrl, useQuicSignal)
        val serverHost = tlsServerHostForManualConnect(connectUrl).ifEmpty {
            runCatching { connectUrl.toUri().host }.getOrNull().orEmpty()
        }
        if (serverHost.isEmpty()) {
            failWith(StartCallException(getString(R.string.call_params_url_exception_tip)))
            return false
        }
        val certPem = callTlsProvider.trustedCert
        if (certPem.isEmpty()) {
            L.e { "[Call] manualSwitch trusted cert is empty" }
            failWith(StartCallException(getString(R.string.call_params_startcall_exception_tip)))
            return false
        }
        return try {
            L.i { "[Call] manualSwitch connect url=$connectUrl serverHost=$serverHost quic=$useQuicSignal" }
            roomCtl.connect(
                serverHost,
                connectUrl,
                certPem,
                appToken,
                callParams,
                useQuicSignal,
            ) { t -> throw t }
            LCallEngine.setConnectedServerUrl(connectUrl)
            isRetryUrlConnecting = false
            roomCtl.updateCallStatus(CallStatus.RECONNECTED)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            
            
            
            
            isManualSwitchReconnecting = false
            
            
            
            when (classifyConnectFailure(e)) {
                ConnectFailureCategory.TRANSIENT -> {
                    L.e { "[Call] manualSwitch transient url=$connectUrl err=${e.message}" }
                    reportCertRiskIfNeeded(e, serverHost)
                    LCallEngine.reportConnectionFailure(connectUrl)
                    roomCtl.disconnectQuietly()
                    failWith(ServerConnectionException(getString(R.string.call_connect_timeout_tip)))
                }
                ConnectFailureCategory.SERVER_VERDICT -> {
                    L.w { "[Call] manualSwitch server verdict status=${serverVerdictStatus(e)} url=$connectUrl" }
                    failWith(StartCallException(e.message))
                }
                ConnectFailureCategory.PRECONDITION -> {
                    L.e { "[Call] manualSwitch precondition url=$connectUrl err=${e.message}" }
                    failWith(ServerConnectionException(getString(R.string.call_params_startcall_exception_tip), e))
                }
            }
            false
        }
    }

    



    fun resolveManualConnectUrl(node: ServerNode?, useQuicSignal: Boolean): String? {
        val n = node ?: return null
        return if (useQuicSignal) {
            
            
            
            
            
            
            if (ProxyConfigProvider.isProxyForCallQuicEnabled) {
                return if (n.domain.isNotBlank())
                    MeetingConnectionPlanner.normalizeConnectUrl(n.domain)
                else {
                    L.w { "[Call] proxy-QUIC but node '${n.name}' has no domain; refusing QUIC-by-IP" }
                    null
                }
            }
            val ip = n.addrs.firstOrNull { it.isNotBlank() }?.trim()
            when {
                !ip.isNullOrEmpty() -> MeetingConnectionPlanner.normalizeConnectUrl(ip)
                n.domain.isNotBlank() -> {
                    L.w {
                        "[Call] QUIC requested but node '${n.name}' has no IPs, falling back to domain=${n.domain}"
                    }
                    MeetingConnectionPlanner.normalizeConnectUrl(n.domain)
                }
                else -> null
            }
        } else {
            if (n.domain.isNotBlank()) MeetingConnectionPlanner.normalizeConnectUrl(n.domain) else null
        }
    }

    




    

    fun observeManualSwitchReconnect(
        scope: CoroutineScope,
        callIntent: CallIntent,
        roomIdGetter: () -> String?,
        showToast: (String) -> Unit,
    ): Job = scope.launch {
        combine(
            LCallEngine.serverNodeSelected,
            LCallEngine.connectionType,
        ) { node, connectionType -> node to connectionType }
            .collect { (selectedNode, _) ->
                val status = roomCtl.callStatus.value
                if (status != CallStatus.CONNECTED && status != CallStatus.RECONNECTED) return@collect

                
                val useQuicSignal = LCallEngine.isUseQuicSignal()
                val connectionTypeChanged = roomCtl.isUseQuicSignal() != useQuicSignal
                if (selectedNode == null && !connectionTypeChanged) return@collect

                
                
                
                
                
                
                
                
                
                if (!roomCtl.hasRemoteParticipants()) {
                    L.w {
                        "[call] manualSwitchReconnect rejected: only self in room, node=${selectedNode?.name} useQuic=$useQuicSignal"
                    }
                    showToast(getString(R.string.call_server_node_switch_forbid_only_self))
                    rollbackManualSwitch(selectedNode, connectionTypeChanged)
                    return@collect
                }

                val targetNode = selectedNode ?: inferConnectedNode()
                val effective = resolveManualConnectUrl(targetNode, useQuicSignal)
                if (effective.isNullOrEmpty()) {
                    L.w {
                        "[call] manualSwitchReconnect no effective url, node=${targetNode?.name} useQuic=$useQuicSignal"
                    }
                    showToast(getString(R.string.call_server_node_switch_unavailable))
                    rollbackManualSwitch(selectedNode, connectionTypeChanged)
                    return@collect
                }
                L.i {
                    "[call] manualSwitchReconnect node=${targetNode?.name} useQuic=$useQuicSignal effective=$effective"
                }
                roomCtl.updateCallStatus(CallStatus.SWITCHING_SERVER)

                val body = StartCallRequestBody(
                    callIntent.callType,
                    LCallConstants.CALL_VERSION,
                    System.currentTimeMillis(),
                    conversation = callIntent.conversationId,
                    roomId = roomIdGetter(),
                )
                val joinCallParams = createStartCallParams(body)
                
                
                
                
                
                
                
                
                
                
                isManualSwitchReconnecting = true
                scope.launch(Dispatchers.IO) {
                    
                    
                    
                    
                    roomCtl.disconnectQuietly()
                    awaitDisconnectedSettle()
                    connectToRoomManualSwitch(effective, joinCallParams, useQuicSignal)
                }
            }
    }

    













    private suspend fun awaitDisconnectedSettle() {
        val deadline = System.currentTimeMillis() + MANUAL_SWITCH_DISCONNECT_WAIT_MS
        while (!roomCtl.isRoomDisconnected() && System.currentTimeMillis() < deadline) {
            delay(MANUAL_SWITCH_DISCONNECT_POLL_MS)
        }
        delay(MANUAL_SWITCH_SETTLE_DELAY_MS)
    }

    








    private fun rollbackManualSwitch(selectedNode: ServerNode?, connectionTypeChanged: Boolean) {
        if (connectionTypeChanged) {
            LCallEngine.setSelectedConnectMode(
                if (roomCtl.isUseQuicSignal()) CONNECTION_TYPE.HTTP3_QUIC else CONNECTION_TYPE.WEB_SOCKET,
                fromUserSelection = false,
            )
        }
        if (selectedNode != null) {
            LCallEngine.resetSelectedServerNode()
        }
    }

    fun inferConnectedNode(): ServerNode? {
        val connectedUrl = LCallEngine.serverUrlConnected.value ?: return null
        val host = runCatching { connectedUrl.toUri().host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty()) return null
        val cached = LCallManager.getCachedServiceUrls() ?: return null
        val candidates = buildList {
            cached.primary?.let { add(it to true) }
            cached.fallback.forEach { if (it != null) add(it to false) }
        }
        val match = candidates.firstOrNull { (u, _) ->
            u.domain.trim().equals(host, ignoreCase = true) ||
                u.addrs.any { it.trim().equals(host, ignoreCase = true) }
        } ?: return null
        val (urlInfo, isPrimary) = match
        val domain = urlInfo.domain.trim()
        val connectUrl = MeetingConnectionPlanner.normalizeConnectUrl(domain) ?: "https://$domain"
        return ServerNode(
            name = urlInfo.region.ifEmpty { domain },
            url = connectUrl,
            flag = "",
            region = urlInfo.region,
            domain = domain,
            addrs = urlInfo.addrs,
            isPrimary = isPrimary,
        )
    }

    private fun failWith(error: Throwable) {
        roomCtl.updateCallStatus(CallStatus.CONNECTED_FAILED)
        roomCtl.collectError(error)
    }

    




    private fun tlsServerHostForManualConnect(connectUrl: String): String {
        val host = runCatching { connectUrl.toUri().host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty()) return host
        if (MeetingConnectionPlanner.isIpHost(host)) {
            val domain = resolveDomainForIpHost(host)
            if (domain.isNotEmpty()) return domain
        }
        return host
    }

    




    private fun enforceWssDomainUrl(normalizedUrl: String, useQuicSignal: Boolean): String {
        if (useQuicSignal) return normalizedUrl
        val host = runCatching { normalizedUrl.toUri().host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty() || !MeetingConnectionPlanner.isIpHost(host)) return normalizedUrl
        val domain = resolveDomainForIpHost(host)
        if (domain.isEmpty()) return normalizedUrl
        return MeetingConnectionPlanner.normalizeConnectUrl(domain) ?: normalizedUrl
    }

    





    private fun resolveDomainForIpHost(ipHost: String): String {
        val cached = LCallManager.getCachedServiceUrls()
        if (cached != null) {
            val nodes = buildList {
                cached.primary?.let { add(it) }
                cached.fallback.forEach { if (it != null) add(it) }
            }
            val owning = nodes.firstOrNull { node ->
                node.addrs.any { it.trim().equals(ipHost, ignoreCase = true) }
            }
            if (owning != null) {
                val d = owning.domain.trim()
                if (d.isNotEmpty()) return d
            }
        }
        val selectedDomain = LCallEngine.serverNodeSelected.value?.domain?.trim().orEmpty()
        if (selectedDomain.isNotEmpty()) return selectedDomain
        return cached?.primary?.domain?.trim().orEmpty()
    }

    private fun reportConnectFail(att: ConnectionAttempt, errorMsg: String) {
        statisticsLogManager.reportConnectFail(att.connectUrl, att.serverHost, att.useQuic, att.nodeType, errorMsg)
    }

    private fun reportChannelDowngradeIfNeeded(
        successAtt: ConnectionAttempt,
        hadQuicFailure: Boolean,
        hadPrimaryFailure: Boolean,
        lastFailedErrorMsg: String,
    ) {
        statisticsLogManager.reportChannelDowngradeIfNeeded(
            successAtt.connectUrl, successAtt.serverHost, successAtt.useQuic, successAtt.nodeType,
            hadQuicFailure, hadPrimaryFailure, lastFailedErrorMsg,
        )
    }

    
    internal companion object {
        
        const val MANUAL_SWITCH_DISCONNECT_POLL_MS = 20L

        




        const val MANUAL_SWITCH_DISCONNECT_WAIT_MS = 500L

        




        const val MANUAL_SWITCH_SETTLE_DELAY_MS = 300L

        


        const val CONNECT_BUDGET_MS = 60_000L

        





        const val MAX_TRANSIENT_FAILURES = 20
    }
}
