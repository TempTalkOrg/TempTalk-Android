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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LCallServerNodeModel(application: Application) : AndroidViewModel(application) {

    val serverUrlConnected = LCallEngine.serverUrlConnected

    val serverNodeSelected = LCallEngine.serverNodeSelected

    val connectionType = LCallEngine.connectionType

    private val _serverNodes = MutableStateFlow<List<ServerNode>>(emptyList())
    val serverNodes: StateFlow<List<ServerNode>> get() = _serverNodes

    private val _serverNodeConnected = MutableStateFlow<ServerNode?>(null)
    val serverNodeConnected: StateFlow<ServerNode?> get() = _serverNodeConnected

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
            _isLoading.value = false
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            LCallManager.fetchCallServiceUrlAndCache()
            val serviceUrls = LCallManager.getCachedServiceUrls()
                ?: DefaultGlobalConfigCallServiceUrlsReader.read(getApplication())
            if (serviceUrls != null) {
                val nodes = buildServerNodes(serviceUrls)
                _serverNodes.value = nodes
                updateConnectedNode(nodes)
            }
            _isLoading.value = false
        }
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
        return DefaultGlobalConfigCallServiceUrlsReader.read(getApplication())
    }

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
