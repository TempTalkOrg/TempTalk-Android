package com.difft.android.call.node

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.BaseActivity
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallEngine
import com.difft.android.call.R
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.ServerNode
import com.difft.android.call.viewModelByFactory
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LCallServerNodeActivity : BaseActivity() {

    private val viewModel: LCallServerNodeModel by viewModelByFactory {
        LCallServerNodeModel(application = application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NetworkDashboardUI() }
    }

    @Composable
    fun NetworkDashboardUI() {
        val servers by viewModel.serverNodes.collectAsState()
        val serverNodeConnected by viewModel.serverNodeConnected.collectAsState()
        val serverNodeSelected by viewModel.serverNodeSelected.collectAsState()
        val connectionType by viewModel.connectionType.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
                .padding(top = topInset),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (serverNodeConnected != null) {
                ConnectionStatusCard(serverNodeConnected, connectionType, connected = true)
            } else {
                val server = serverNodeSelected ?: servers.firstOrNull()
                ConnectionStatusCard(server, connectionType, connected = false)
            }
            ServerSelectionCard(
                servers = servers.toList(),
                isLoading = isLoading,
                onServerSelected = { server ->
                    ToastUtil.show(getString(R.string.call_server_node_select_route, server.name))
                    LCallEngine.setSelectedServerNode(server)
                },
                onRefresh = { viewModel.refresh() },
            )
        }
    }

    @Composable
    fun ConnectionStatusCard(server: ServerNode?, connectionType: CONNECTION_TYPE, connected: Boolean) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = Color(0xFF2196F3),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        server?.name ?: "----",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (connected) Color.Green else Color.Red)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(if (connected) R.string.call_server_node_connected else R.string.call_server_node_disconnected),
                            fontSize = 14.sp,
                            color = Color.Gray,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = if (connectionType == CONNECTION_TYPE.HTTP3_QUIC) "HTTP/3" else "WebSocket",
                        fontSize = 13.sp,
                        color = Color.DarkGray,
                    )
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = connectionType == CONNECTION_TYPE.HTTP3_QUIC,
                        onCheckedChange = { checked ->
                            val protocol = if (checked) CONNECTION_TYPE.HTTP3_QUIC else CONNECTION_TYPE.WEB_SOCKET
                            LCallEngine.setSelectedConnectMode(protocol, fromUserSelection = true)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF2196F3),
                            uncheckedThumbColor = Color.LightGray,
                        ),
                    )
                }
            }
        }
    }

    @Composable
    fun ServerSelectionCard(
        servers: List<ServerNode>,
        isLoading: Boolean,
        onServerSelected: (ServerNode) -> Unit,
        onRefresh: () -> Unit,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.call_server_node_select_server),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color(0xFF2196F3),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                servers.forEachIndexed { index, server ->
                    if (index > 0) {
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { onServerSelected(server) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(server.flag, fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(server.name, fontSize = 16.sp)
                                Text(
                                    server.domain,
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                )
                                val ipsText = if (server.addrs.isEmpty()) {
                                    stringResource(R.string.call_server_node_no_ips)
                                } else {
                                    "${stringResource(R.string.call_server_node_ips_label)}: ${server.addrs.joinToString(", ")}"
                                }
                                Text(
                                    ipsText,
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        NodeRoleBadge(isPrimary = server.isPrimary)
                    }
                }
            }
        }
    }

    @Composable
    private fun NodeRoleBadge(isPrimary: Boolean) {
        val bg = if (isPrimary) Color(0xFF2196F3) else Color(0xFF9E9E9E)
        val label = stringResource(
            if (isPrimary) R.string.call_server_node_primary
            else R.string.call_server_node_fallback
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                label,
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}
