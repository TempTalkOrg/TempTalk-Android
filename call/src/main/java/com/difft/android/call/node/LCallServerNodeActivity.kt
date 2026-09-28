package com.difft.android.call.node

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.compose.DifftScreen
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallEngine
import com.difft.android.call.R
import com.difft.android.call.data.CONNECTION_TYPE
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LCallServerNodeActivity : BaseActivity() {

    private val viewModel: LCallServerNodeModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DifftTheme {
                ServerNodeScreen()
            }
        }
    }

    @Composable
    private fun ServerNodeScreen() {
        val servers by viewModel.serverNodes.collectAsState()
        val upstreamServers by viewModel.upstreamServerNodes.collectAsState()
        val showUpstreamSection by viewModel.showUpstreamSection.collectAsState()
        val serverNodeConnected by viewModel.serverNodeConnected.collectAsState()
        val serverNodeSelected by viewModel.serverNodeSelected.collectAsState()
        val connectionType by viewModel.connectionType.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()

        Box(Modifier.systemBarsPadding()) {
            DifftScreen(
                title = stringResource(R.string.call_server_node_page_title),
                onNavigateBack = { finish() },
                containerColor = DifftTheme.colors.bg,
            ) { padding ->
                LCallServerNodeContent(
                    servers = servers,
                    upstreamServers = upstreamServers,
                    showUpstreamSection = showUpstreamSection,
                    connectedNode = serverNodeConnected,
                    selectedNode = serverNodeSelected,
                    connectionType = connectionType,
                    isLoading = isLoading,
                    onServerSelected = { server ->
                        L.i { "[Call] server node selected region=${server.region} primary=${server.isPrimary}" }
                        ToastUtil.show(getString(R.string.call_server_node_select_route, server.name))
                        LCallEngine.setSelectedServerNode(server)
                    },
                    onProtocolChange = { checked ->
                        if (checked && !LCallEngine.isQuicSelectable()) {
                            // QUIC unavailable under the current proxy: refuse the toggle without
                            // mutating state so the controlled switch snaps back to OFF.
                            ToastUtil.show(getString(R.string.call_server_node_quic_unavailable))
                        } else {
                            val protocol =
                                if (checked) CONNECTION_TYPE.HTTP3_QUIC else CONNECTION_TYPE.WEB_SOCKET
                            LCallEngine.setSelectedConnectMode(protocol, fromUserSelection = true)
                        }
                    },
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}
