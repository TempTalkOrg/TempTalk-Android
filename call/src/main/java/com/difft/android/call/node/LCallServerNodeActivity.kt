package com.difft.android.call.node

import android.os.Bundle
import androidx.activity.compose.setContent
import com.difft.android.base.BaseActivity
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.difft.android.call.R
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.CountdownTimer
import com.difft.android.base.user.PromptReminder
import com.difft.android.base.user.defaultBarrageTexts
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallEngine
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.ServerNode
import com.difft.android.call.viewModelByFactory
import com.difft.android.network.config.GlobalConfigsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.getValue


@AndroidEntryPoint
class LCallServerNodeActivity : BaseActivity() {

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    private val callConfig: CallConfig by lazy {
        globalConfigsManager.getNewGlobalConfigs()?.data?.call ?: CallConfig(autoLeave = AutoLeave(promptReminder = PromptReminder()), chatPresets = defaultBarrageTexts, chat = CallChat(), countdownTimer = CountdownTimer())
    }

    private val viewModel: LCallServerNodeModel by viewModelByFactory {
        LCallServerNodeModel(
            application = application,
            callConfig = callConfig
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()
    }

    private fun initView() {
        setContent {
            NetworkDashboardUI()
        }
    }

    @Composable
    fun NetworkDashboardUI() {

        val servers by viewModel.serverNodes.collectAsState()
        val serverNodeConnected by viewModel.serverNodeConnected.collectAsState()
        val serverNodeSelected by viewModel.serverNodeSelected.collectAsState()
        val connectionType by viewModel.connectionType.collectAsState()
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
                .padding(top = topInset),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if(serverNodeConnected != null) {
                // 顶部连接状态
                ConnectionStatusCard(serverNodeConnected, connectionType, true)
            }else {
                val server = serverNodeSelected ?: servers.firstOrNull()
                ConnectionStatusCard(server, connectionType, false)
            }
            // 线路选择卡片
            ServerSelectionCard(servers.toList(), onServerSelected = { server ->
                ToastUtil.show(getString(R.string.call_server_node_select_route, server.name))
                LCallEngine.setSelectedServerNode(server)
            })
        }
    }

    // 顶部状态
    @Composable
    fun ConnectionStatusCard(server: ServerNode?, connectionType: CONNECTION_TYPE, connected: Boolean) {
        Card(
            modifier = Modifier.Companion.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Row(
                modifier = Modifier.Companion
                    .padding(16.dp),
                verticalAlignment = Alignment.Companion.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = Color(0xFF2196F3),
                    modifier = Modifier.Companion.size(32.dp)
                )
                Spacer(modifier = Modifier.Companion.width(12.dp))
                Column {
                    val serverName = server?.name ?: "----"
                    Text(serverName, fontWeight = FontWeight.Companion.Bold, fontSize = 18.sp)
                    Row(verticalAlignment = Alignment.Companion.CenterVertically) {
                        Box(
                            modifier = Modifier.Companion
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (connected) Color.Companion.Green else Color.Companion.Red)
                        )
                        Spacer(Modifier.Companion.width(6.dp))
                        Text(
                            stringResource(if (connected) R.string.call_server_node_connected else R.string.call_server_node_disconnected),
                            fontSize = 14.sp,
                            color = Color.Companion.Gray
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
                        color = Color.DarkGray
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
                            uncheckedThumbColor = Color.LightGray
                        )
                    )
                }
            }
        }
    }

    @Composable
    fun ServerSelectionCard(servers: List<ServerNode>, onServerSelected: (ServerNode) -> Unit) {
        Card(
            modifier = Modifier.Companion.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.Companion
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.call_server_node_select_server), fontWeight = FontWeight.Companion.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.Companion.height(12.dp))

                servers.forEach { server ->
                    Row(
                        modifier = Modifier.Companion
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                onServerSelected(server)
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Companion.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.Companion.CenterVertically) {
                            Text(server.flag, fontSize = 20.sp)
                            Spacer(Modifier.Companion.width(8.dp))
                            Text(server.name, fontSize = 16.sp)
                        }
                        Row(verticalAlignment = Alignment.Companion.CenterVertically) {
                            Text(
                                "${server.ping} ms",
                                color = Color.Companion.Gray,
                                fontSize = 14.sp
                            )
                            if (server.recommended) {
                                Spacer(Modifier.Companion.width(8.dp))
                                Box(
                                    modifier = Modifier.Companion
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                        .background(Color(0xFF2196F3))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.call_server_node_recommended), color = Color.Companion.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }


}