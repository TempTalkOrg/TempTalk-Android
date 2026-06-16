package com.difft.android.call.connect

import com.difft.android.base.utils.globalServices

import android.content.Context
import androidx.core.net.toUri
import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.log.lumberjack.L
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
import io.livekit.android.room.RoomException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.Volatile

/**
 * Coordinates connection lifecycle and failover for a single call session.
 *
 * Responsibilities:
 *  - Multi-phase failover connect ([connectToRoomWithFailover]).
 *  - Single-URL reconnect after a user-initiated node/connection-mode switch
 *    ([connectToRoomManualSwitch]).
 *  - URL / TLS-host resolution that honours the WSS(domain-only) vs QUIC(IP direct)
 *    rule when the caller changes settings mid-call ([resolveManualConnectUrl],
 *    [inferConnectedNode]).
 *
 * Extracted from `LCallViewModel` so the ViewModel can stay focused on call orchestration
 * and UI state, and to keep each file within the project's 500-line limit.
 *
 * Thread-safety: instances are expected to be used from a single call session; the only
 * shared state is [isRetryUrlConnecting] which is marked [Volatile] so the Room event
 * collector on the main thread observes the latest value without locking.
 */
internal class CallConnectionCoordinator(
    private val appContext: Context,
    private val roomCtl: CallRoomController,
    private val callTlsProvider: CallTlsProvider,
) {

    /**
     * True while a failover / manual-switch attempt is in progress. The ViewModel reads
     * this to suppress spurious `Disconnected` / `FailedToConnect` room events that are
     * an expected part of the retry loop.
     */
    @Volatile
    var isRetryUrlConnecting: Boolean = false
        private set

    /**
     * Failover & retry strategy:
     *   [LCallManager.ensureCallServiceUrlsForCall] → multi-node QUIC/WSS sequence → backoff →
     *   force-refresh config → assets fallback, then retry.
     *
     * Returns `true` as soon as any attempt succeeds; on terminal or exhaustion it
     * reports a final error via [roomCtl] and returns `false`.
     */
    suspend fun connectToRoomWithFailover(
        callParams: ByteArray?,
        useQuic: Boolean,
    ): Boolean {
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
        val appToken = (globalServices.userManager.getUserData()?.microToken ?: "")
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
            ?: DefaultGlobalConfigCallServiceUrlsReader.read(appContext)

        if (serviceUrls == null) {
            failWith(StartCallException(getString(R.string.call_params_url_exception_tip)))
            return false
        }

        var failureCount = 0
        isRetryUrlConnecting = true
        for (phase in 0 until 3) {
            if (phase == 1) {
                delay(2_000L)
                LCallManager.fetchCallServiceUrlAndCache()
                serviceUrls = LCallManager.getCachedServiceUrls() ?: serviceUrls
            } else if (phase == 2) {
                delay(5_000L)
                serviceUrls = DefaultGlobalConfigCallServiceUrlsReader.read(appContext) ?: serviceUrls
            }

            val su = serviceUrls ?: break
            val attempts = MeetingConnectionPlanner.buildAttempts(su, useQuic)
            if (attempts.isEmpty()) {
                L.w { "[Call] connectToRoomWithFailover phase=$phase no connection attempts" }
                continue
            }

            for ((idx, att) in attempts.withIndex()) {
                if (failureCount > 0) {
                    delay(ConnectionBackoff.delayMsBeforeRetryAfterFailure(failureCount))
                }
                L.i {
                    "[Call] meeting connect phase=$phase ${idx + 1}/${attempts.size} url=${att.connectUrl} quic=${att.useQuic}"
                }

                var transientFailure = false
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
                    when (t) {
                        is SocketTimeoutException, is RoomException.ConnectTimeoutException, is SSLHandshakeException, is UnknownHostException -> {
                            LCallEngine.reportConnectionFailure(att.connectUrl)
                            roomCtl.room.disconnect()
                            transientFailure = true
                        }
                        is RoomException.NoAuthException, is RoomException.StartCallException, is StartCallException -> {
                            terminalError = StartCallException(t.message)
                        }
                        else -> {
                            terminalError = ServerConnectionException(t.message)
                        }
                    }
                }

                if (!transientFailure && terminalError == null) {
                    LCallEngine.setConnectedServerUrl(att.connectUrl)
                    isRetryUrlConnecting = false
                    return true
                }
                terminalError?.let { err ->
                    isRetryUrlConnecting = false
                    failWith(err)
                    return false
                }

                failureCount++
                if (failureCount == 6) {
                    L.w {
                        "[Call] meeting connect transient failures exceeded 5 (count=$failureCount, phase=$phase, lastUrl=${att.connectUrl}); analytics/report TODO"
                    }
                }
                isRetryUrlConnecting = idx < attempts.lastIndex || phase < 2
                if (failureCount > 20) {
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

    /**
     * Single-URL reconnect after the user switches node / connection mode — no multi-phase
     * failover. Shares certificate source and error classification with
     * [connectToRoomWithFailover].
     */
    suspend fun connectToRoomManualSwitch(
        serverUrl: String,
        callParams: ByteArray?,
        useQuicSignal: Boolean,
    ): Boolean {
        if (callParams == null) {
            failWith(StartCallException(getString(R.string.call_params_startcall_exception_tip)))
            return false
        }
        val appToken = (globalServices.userManager.getUserData()?.microToken ?: "")
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
            when (e) {
                is SocketTimeoutException, is SSLHandshakeException, is UnknownHostException -> {
                    L.e { "[Call] manualSwitch transient url=$connectUrl err=${e.message}" }
                    LCallEngine.reportConnectionFailure(connectUrl)
                    roomCtl.room.disconnect()
                    failWith(ServerConnectionException(getString(R.string.call_connect_timeout_tip)))
                }
                is RoomException.NoAuthException, is RoomException.StartCallException, is StartCallException -> {
                    failWith(StartCallException(e.message))
                }
                else -> {
                    failWith(ServerConnectionException(e.message))
                }
            }
            false
        }
    }

    /**
     * Picks a connect URL for the given node respecting the connection mode rule.
     * Returns `null` when no viable URL can be produced (e.g. node has neither IP nor domain).
     */
    fun resolveManualConnectUrl(node: ServerNode?, useQuicSignal: Boolean): String? {
        val n = node ?: return null
        return if (useQuicSignal) {
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

    /**
     * Rebuilds the currently-connected [ServerNode] from [LCallEngine.serverUrlConnected] by
     * matching the URL host against cached service URLs (domain or addrs). Used when the user
     * only toggles the connection mode without selecting a node.
     */
    /**
     * Listens for user-initiated server node / connection mode changes during an active call
     * and triggers a reconnect with the updated configuration. Previously
     * `LCallViewModel.registerManualSwitchReconnect` — merged here so the full manual-switch
     * chain (URL resolution + reconnect + status bookkeeping) lives in one place.
     *
     * Connection rule enforcement:
     *  - WebSocket (useQuicSignal=false) → connect via the node's domain.
     *  - HTTP/3 QUIC (useQuicSignal=true) → connect via the node's first IP in `addrs`;
     *    if no IP is available, fall back to the domain (with a warning log).
     */
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

                // Honors the proxy QUIC→WSS override centrally (see LCallEngine.isUseQuicSignal).
                val useQuicSignal = LCallEngine.isUseQuicSignal()
                val connectionTypeChanged = roomCtl.isUseQuicSignal() != useQuicSignal
                if (selectedNode == null && !connectionTypeChanged) return@collect

                // Guard: when only self is in the room, tearing down causes the server to
                // end the call and the subsequent reconnect will be rejected with status
                // 22001 ("Invalid Call, maybe expired"). Reject up-front and roll back
                // engine state so the UI reflects the still-active connection.
                if (roomCtl.room.remoteParticipants.isEmpty()) {
                    L.w {
                        "[call] manualSwitchReconnect rejected: only self in room, node=${selectedNode?.name} useQuic=$useQuicSignal"
                    }
                    showToast(getString(R.string.call_server_node_switch_forbid_only_self))
                    if (connectionTypeChanged) {
                        LCallEngine.setSelectedConnectMode(
                            if (roomCtl.isUseQuicSignal()) CONNECTION_TYPE.HTTP3_QUIC else CONNECTION_TYPE.WEB_SOCKET,
                            fromUserSelection = false,
                        )
                    }
                    if (selectedNode != null) {
                        LCallEngine.resetSelectedServerNode()
                    }
                    return@collect
                }

                val targetNode = selectedNode ?: inferConnectedNode()
                val effective = resolveManualConnectUrl(targetNode, useQuicSignal)
                if (effective.isNullOrEmpty()) {
                    L.w {
                        "[call] manualSwitchReconnect no effective url, node=${targetNode?.name} useQuic=$useQuicSignal"
                    }
                    return@collect
                }
                L.i {
                    "[call] manualSwitchReconnect node=${targetNode?.name} useQuic=$useQuicSignal effective=$effective"
                }
                roomCtl.updateCallStatus(CallStatus.SWITCHING_SERVER)
                roomCtl.room.disconnect()

                val body = StartCallRequestBody(
                    callIntent.callType,
                    LCallConstants.CALL_VERSION,
                    System.currentTimeMillis(),
                    conversation = callIntent.conversationId,
                    roomId = roomIdGetter(),
                )
                val joinCallParams = createStartCallParams(body)
                scope.launch(Dispatchers.IO) {
                    connectToRoomManualSwitch(effective, joinCallParams, useQuicSignal)
                }
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

    /**
     * For IP direct connections, the URI's host is an IP; TLS requires the certificate domain.
     * Look up the node whose `addrs` contains this IP from cached service URLs first, then fall
     * back to the currently selected node's domain; otherwise the primary domain as a last resort.
     */
    private fun tlsServerHostForManualConnect(connectUrl: String): String {
        val host = runCatching { connectUrl.toUri().host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty()) return host
        if (MeetingConnectionPlanner.isIpHost(host)) {
            val domain = resolveDomainForIpHost(host)
            if (domain.isNotEmpty()) return domain
        }
        return host
    }

    /**
     * WSS (WebSocket) must connect via the certificate domain — IP direct connect is QUIC-only.
     * If the incoming URL is IP-based but the user selected WebSocket mode, rewrite the URL to
     * use that IP's owning node domain before connecting.
     */
    private fun enforceWssDomainUrl(normalizedUrl: String, useQuicSignal: Boolean): String {
        if (useQuicSignal) return normalizedUrl
        val host = runCatching { normalizedUrl.toUri().host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty() || !MeetingConnectionPlanner.isIpHost(host)) return normalizedUrl
        val domain = resolveDomainForIpHost(host)
        if (domain.isEmpty()) return normalizedUrl
        return MeetingConnectionPlanner.normalizeConnectUrl(domain) ?: normalizedUrl
    }

    /**
     * Resolves the domain that owns the given IP host. Priority:
     *   1. Node (primary or fallback) in cached service URLs whose `addrs` contains this IP.
     *   2. User-selected server node's domain (if any).
     *   3. Cached primary node's domain (last resort).
     */
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
}
