package com.difft.android.call.node

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.call.UrlInfo
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallEngine
import com.difft.android.call.LCallManager
import com.difft.android.call.connect.DefaultGlobalConfigCallServiceUrlsReader
import com.difft.android.call.connect.MeetingConnectionPlanner
import com.difft.android.call.data.ServerNode
import com.difft.android.network.proxy.ProxyConfigProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LCallServerNodeModel @Inject constructor(application: Application) : AndroidViewModel(application) {

    val serverUrlConnected = LCallEngine.serverUrlConnected

    val serverNodeSelected = LCallEngine.serverNodeSelected

    val connectionType = LCallEngine.connectionType

    private val _serverNodes = MutableStateFlow<List<ServerNode>>(emptyList())
    val serverNodes: StateFlow<List<ServerNode>> get() = _serverNodes

    private val _serverNodeConnected = MutableStateFlow<ServerNode?>(null)
    val serverNodeConnected: StateFlow<ServerNode?> get() = _serverNodeConnected

    /**
     * Real upstream servers (domain + IPs) from `serviceurl/v2`, surfaced only while
     * the proxy is protecting call IPs. In that mode [serverNodes] shows the proxy
     * tunnel domains (no IPs, used for the actual connection); this exposes the real
     * servers the backend returned so the diagnostics screen isn't misleading. Empty
     * when the proxy is off (the primary list already carries the real IPs).
     */
    private val _upstreamServerNodes = MutableStateFlow<List<ServerNode>>(emptyList())
    val upstreamServerNodes: StateFlow<List<ServerNode>> get() = _upstreamServerNodes

    /**
     * Whether to render the upstream-servers section at all — true only while the
     * proxy is protecting call IPs. Decoupled from [upstreamServerNodes] being empty
     * so the section can show an explicit empty-state (e.g. after a failed warm-up
     * fetch) instead of silently disappearing.
     */
    private val _showUpstreamSection = MutableStateFlow(false)
    val showUpstreamSection: StateFlow<Boolean> get() = _showUpstreamSection

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> get() = _isLoading

    init {
        loadServerNodes()
        observeConnectedUrl()
    }

    private fun loadServerNodes() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val serviceUrls = resolveServiceUrls()
            if (serviceUrls != null) {
                val nodes = buildServerNodes(serviceUrls)
                _serverNodes.value = nodes
                updateConnectedNode(nodes)
            }
            loadUpstreamNodes()
            _isLoading.value = false
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            LCallManager.fetchCallServiceUrlAndCache()
            val serviceUrls = LCallManager.getCachedServiceUrls()
                ?: embeddedServiceUrlsFallbackOrNull()
            if (serviceUrls != null) {
                val nodes = buildServerNodes(serviceUrls)
                _serverNodes.value = nodes
                updateConnectedNode(nodes)
            }
            loadUpstreamNodes()
            _isLoading.value = false
        }
    }

    /**
     * Populates [upstreamServerNodes] with the real `serviceurl/v2` servers (domain +
     * IPs) while the proxy is protecting call IPs — [serverNodes] only shows the
     * tunnel domains in that mode. Cleared when the proxy is off, since the primary
     * list already carries the real IPs.
     *
     * When proxied and the upstream cache is still empty (e.g. fresh login before any
     * foreground refresh has run), warms it with a single [fetchCallServiceUrlAndCache]
     * so the diagnostics card isn't blank on first entry. Gated on empty-cache to avoid
     * a redundant request on every open; coalesced (5s) with any concurrent fetch.
     */
    private suspend fun loadUpstreamNodes() {
        val proxied = ProxyConfigProvider.isProxyActiveForCall
        _showUpstreamSection.value = proxied
        if (!proxied) {
            _upstreamServerNodes.value = emptyList()
            return
        }
        var nodes = LCallManager.getUpstreamCachedServiceUrls()?.let { buildServerNodes(it) }.orEmpty()
        if (nodes.isEmpty()) {
            runCatching { LCallManager.fetchCallServiceUrlAndCache() }
            nodes = LCallManager.getUpstreamCachedServiceUrls()?.let { buildServerNodes(it) }.orEmpty()
        }
        _upstreamServerNodes.value = nodes
    }

    private fun observeConnectedUrl() {
        viewModelScope.launch {
            serverUrlConnected.collect { connectedUrl ->
                L.d { "[Call] LCallServerNodeModel observeConnectedUrl url=$connectedUrl" }
                _serverNodeConnected.value = findConnectedNode(_serverNodes.value, connectedUrl)
            }
        }
    }

    private suspend fun resolveServiceUrls(): ServiceUrls? {
        LCallManager.getCachedServiceUrls()?.let { return it }
        runCatching { LCallManager.ensureCallServiceUrlsForCall(timeoutMs = 10_000L) }
            .getOrNull()?.let { return it }
        return embeddedServiceUrlsFallbackOrNull()
    }

    /**
     * Embedded `serviceUrls` fallback for the node-list display — gated OFF while
     * the proxy is active. Under the proxy the call domain comes solely from
     * `proxy.tunnelDomains.call` (via [LCallManager.getCachedServiceUrls] /
     * [LCallManager.ensureCallServiceUrlsForCall]); the embedded `serviceUrls`
     * block is a separate source that can drift from the tunnel-host whitelist,
     * so we never surface it as a node while proxied (mirrors
     * `CallConnectionCoordinator.embeddedServiceUrlsFallbackOrNull`).
     */
    private fun embeddedServiceUrlsFallbackOrNull(): ServiceUrls? =
        if (ProxyConfigProvider.isProxyActiveForCall) null
        else DefaultGlobalConfigCallServiceUrlsReader.read(getApplication())

    private fun buildServerNodes(serviceUrls: ServiceUrls): List<ServerNode> {
        val nodes = mutableListOf<ServerNode>()
        serviceUrls.primary?.let { nodes += it.toServerNode(isPrimary = true) }
        for (fb in serviceUrls.fallback) {
            if (fb != null) nodes += fb.toServerNode(isPrimary = false)
        }
        return nodes
    }

    private fun UrlInfo.toServerNode(isPrimary: Boolean): ServerNode {
        val connectUrl = MeetingConnectionPlanner.normalizeConnectUrl(domain) ?: "https://$domain"
        return ServerNode(
            name = region.ifEmpty { domain },
            url = connectUrl,
            flag = regionToFlag(region),
            region = region,
            domain = domain,
            addrs = addrs,
            isPrimary = isPrimary,
        )
    }

    private fun updateConnectedNode(nodes: List<ServerNode>) {
        _serverNodeConnected.value = findConnectedNode(nodes, serverUrlConnected.value)
    }

    private fun findConnectedNode(nodes: List<ServerNode>, connectedUrl: String?): ServerNode? {
        if (connectedUrl.isNullOrEmpty()) return null
        return nodes.firstOrNull { node ->
            node.url.equals(connectedUrl, ignoreCase = true) ||
                node.addrs.any {
                    MeetingConnectionPlanner.normalizeConnectUrl(it)
                        ?.equals(connectedUrl, ignoreCase = true) == true
                }
        }
    }

    private fun regionToFlag(region: String): String {
        return when {
            region.contains("AE", ignoreCase = true) || region.contains("UAE", ignoreCase = true) -> "\uD83C\uDDE6\uD83C\uDDEA"
            region.contains("SG", ignoreCase = true) -> "\uD83C\uDDF8\uD83C\uDDEC"
            region.contains("US", ignoreCase = true) -> "\uD83C\uDDFA\uD83C\uDDF8"
            region.contains("JP", ignoreCase = true) -> "\uD83C\uDDEF\uD83C\uDDF5"
            region.contains("HK", ignoreCase = true) -> "\uD83C\uDDED\uD83C\uDDF0"
            else -> "\uD83C\uDF10"
        }
    }
}
