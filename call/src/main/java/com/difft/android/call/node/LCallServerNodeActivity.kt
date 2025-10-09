package com.difft.android.call.node

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallChat
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.CountdownTimer
import com.difft.android.base.user.PromptReminder
import com.difft.android.base.user.defaultBarrageTexts
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallEngine
import com.difft.android.call.data.ServerNode
import com.difft.android.call.viewModelByFactory
import com.difft.android.network.config.GlobalConfigsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.getValue


@AndroidEntryPoint
class LCallServerNodeActivity: AppCompatActivity() {

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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if(serverNodeConnected != null) {
                // 顶部连接状态
                ConnectionStatusCard(serverNodeConnected, true)
            }else {
                val server = serverNodeSelected ?: servers.firstOrNull()
                ConnectionStatusCard(server, false)
            }
            // 线路选择卡片
            ServerSelectionCard(servers.toList(), onServerSelected = { server ->
                ToastUtil.show("选择线路：${server.name}")
                LCallEngine.setSelectedServerNode(server)
            })
        }
    }

    // 顶部状态
    @Composable
    fun ConnectionStatusCard(server: ServerNode?, connected: Boolean) {
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
                            if (connected) "已连接" else "未连接",
                            fontSize = 14.sp,
                            color = Color.Companion.Gray
                        )
                    }
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
                Text("选择服务器", fontWeight = FontWeight.Companion.Bold, fontSize = 16.sp)
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
                                    Text("推荐", color = Color.Companion.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }


}