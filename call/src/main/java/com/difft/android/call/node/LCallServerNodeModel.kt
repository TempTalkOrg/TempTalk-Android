package com.difft.android.call.node

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.call.LCallEngine
import com.difft.android.call.data.ServerNode
import com.difft.android.call.data.SpeedResponseStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.collections.sortedBy


class LCallServerNodeModel(application: Application, callConfig: CallConfig): AndroidViewModel(application)  {

    val serverUrlsSpeedInfo = LCallEngine.serverUrlsSpeedInfo

    val serverUrlConnected = LCallEngine.serverUrlConnected

    val serverNodeSelected = LCallEngine.serverNodeSelected

    val serverNodeConfig = callConfig.callServers?.clusters?.flatMap { cluster ->
        mutableListOf<Pair<String, String>>().apply {
            cluster.global_url?.let { url ->
                add(Pair(url,cluster.id + "_global"))
            }
            cluster.mainland_url?.let { url ->
                add(Pair(url,cluster.id + "_mainland"))
            }
        }
    } ?: emptyList()


    private var _serverNodes = MutableStateFlow<List<ServerNode>>(emptyList())
    val serverNodes: StateFlow<List<ServerNode>> get() = _serverNodes

    private var _serverNodeConnected = MutableStateFlow<ServerNode?>(null)
    val serverNodeConnected: StateFlow<ServerNode?> get() = _serverNodeConnected


    init {
        refreshConnectedServerNode()
        refreshServerNodes()
    }

    fun refreshConnectedServerNode() {
        viewModelScope.launch {
            serverUrlConnected.collect { serverUrl ->
                L.d { "[call] LCallEngine refreshConnectedServerNode serverUrl:${serverUrl}" }
                if(serverUrl.isNullOrEmpty()) {
                    _serverNodeConnected.value = null
                    return@collect
                }
                _serverNodeConnected.value = _serverNodes.value.firstOrNull { it.url == serverUrl }
                L.d { "[call] LCallEngine refreshConnectedServerNode _serverNodeConnected:${_serverNodeConnected.value}" }
            }
        }
    }

    fun refreshServerNodes() {
        viewModelScope.launch {
            serverUrlsSpeedInfo.collect { speedInfos ->
                if(speedInfos.isEmpty()){
                    return@collect
                }
                val serverNodes = mutableListOf<ServerNode>()
                speedInfos.sortedBy { it.lastResponseTime }.forEachIndexed {
                    index, speedInfo ->
                    val name = serverNodeConfig.firstOrNull { speedInfo.url == it.first }?.second ?: "未知节点"
                    val flag = when(name) {
                        "UAE_global","UAE_mainland" -> "🇦🇪"
                        "SG_global","SG_mainland" -> "🇸🇬"
                        else -> "🚀"
                    }
                    val ping = if(speedInfo.status == SpeedResponseStatus.SUCCESS) speedInfo.lastResponseTime else -1
                    serverNodes.add(ServerNode(name, url = speedInfo.url , flag,ping.toInt(), recommended = index == 0))
                }
                _serverNodes.value = serverNodes
                serverUrlConnected.value?.let { serverUrl ->
                    _serverNodeConnected.value = _serverNodes.value.firstOrNull { it.url == serverUrl }
                }
            }
        }
    }


}