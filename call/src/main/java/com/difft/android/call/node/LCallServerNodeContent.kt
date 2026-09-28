package com.difft.android.call.node

import android.content.res.Configuration
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.ServerNode

/** Test handles for the two glyphs that carry no text of their own. */
const val SERVER_NODE_CHECKMARK_TEST_TAG = "server_node_checkmark"
const val SERVER_NODE_REFRESH_TEST_TAG = "server_node_refresh"
const val SERVER_NODE_LOADING_TEST_TAG = "server_node_loading"

/** Protocol names, not translatable copy — they label the transport the call actually uses. */
private const val PROTOCOL_QUIC = "HTTP/3"
private const val PROTOCOL_WEB_SOCKET = "WebSocket"

/**
 * Stateless body of the call-network diagnostics screen. Every interaction is reported through a
 * callback, so this never touches `LCallEngine` and can be rendered from a test.
 */
@Composable
fun LCallServerNodeContent(
    servers: List<ServerNode>,
    upstreamServers: List<ServerNode>,
    showUpstreamSection: Boolean,
    connectedNode: ServerNode?,
    selectedNode: ServerNode?,
    connectionType: CONNECTION_TYPE,
    isLoading: Boolean,
    onServerSelected: (ServerNode) -> Unit,
    onProtocolChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))

        ConnectionStatusGroup(
            // The connected node is the truth; falling back to the selection (then the first known
            // node) keeps the row from reading "----" before the first connection.
            node = connectedNode ?: selectedNode ?: servers.firstOrNull(),
            connected = connectedNode != null,
            connectionType = connectionType,
            onProtocolChange = onProtocolChange,
        )

        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))

        DifftSettingsGroup(
            title = stringResource(R.string.call_server_node_select_server),
            titleTrailing = { RefreshAction(isLoading = isLoading, onRefresh = onRefresh) },
        ) {
            servers.forEach { server ->
                ServerNodeRow(
                    server = server,
                    // An explicit selection wins; before the first selection the connected node
                    // carries the checkmark so the list agrees with the status group above.
                    selected = (selectedNode ?: connectedNode)?.domain == server.domain,
                    onClick = { onServerSelected(server) },
                )
            }
        }

        if (showUpstreamSection) {
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
            DifftSettingsGroup(
                title = stringResource(R.string.call_server_node_upstream_title),
                footer = {
                    DifftSettingsCaption(stringResource(R.string.call_server_node_upstream_hint))
                },
            ) {
                if (upstreamServers.isEmpty()) {
                    DifftSettingsRow(label = stringResource(R.string.call_server_node_upstream_empty))
                } else {
                    // Not selectable: while proxied these servers are not used for the connection.
                    upstreamServers.forEach { server ->
                        ServerNodeRow(server = server, selected = false, onClick = null)
                    }
                }
            }
        }

        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Composable
private fun ConnectionStatusGroup(
    node: ServerNode?,
    connected: Boolean,
    connectionType: CONNECTION_TYPE,
    onProtocolChange: (Boolean) -> Unit,
) = DifftSettingsGroup {
    val quic = connectionType == CONNECTION_TYPE.HTTP3_QUIC
    DifftSettingsRow(
        label = node?.name ?: UNKNOWN_NODE_NAME,
        value = stringResource(
            if (connected) R.string.call_server_node_connected else R.string.call_server_node_disconnected
        ),
        leading = {
            Box(
                Modifier
                    .size(DifftSettingsDefaults.LeadingDotSize)
                    .background(
                        if (connected) DifftTheme.colors.success else DifftTheme.colors.textError,
                        CircleShape,
                    ),
            )
        },
    )
    DifftSwitchRow(
        label = if (quic) PROTOCOL_QUIC else PROTOCOL_WEB_SOCKET,
        checked = quic,
        onCheckedChange = onProtocolChange,
        modifier = Modifier
            .fillMaxWidth()
            .background(DifftSettingsDefaults.RowSurfaceColor),
        contentPadding = DifftSettingsDefaults.RowContentPadding,
    )
}

@Composable
private fun RefreshAction(isLoading: Boolean, onRefresh: () -> Unit) {
    if (isLoading) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(20.dp)
                .testTag(SERVER_NODE_LOADING_TEST_TAG),
            strokeWidth = 2.dp,
            color = DifftTheme.colors.primary,
        )
    } else {
        IconButton(
            onClick = onRefresh,
            modifier = Modifier
                .size(32.dp)
                .testTag(SERVER_NODE_REFRESH_TEST_TAG),
        ) {
            Icon(
                painter = painterResource(com.difft.android.base.R.drawable.base_ic_refresh),
                contentDescription = stringResource(R.string.call_server_node_refresh),
                tint = DifftTheme.colors.icon,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One node: flag, name + role badge, then the tunnel domain and its resolved IPs. Tapping a
 * selectable row routes the next call through that node and marks it with a checkmark.
 */
@Composable
private fun ServerNodeRow(
    server: ServerNode,
    selected: Boolean,
    onClick: (() -> Unit)?,
) = DifftSettingsRow(
    onClick = onClick,
    showChevron = false,
    leading = {
        Text(
            text = server.flag,
            style = DifftSettingsDefaults.LabelTextStyle,
            color = DifftTheme.colors.textPrimary,
        )
    },
    trailing = if (selected) {
        {
            Icon(
                painter = painterResource(com.difft.android.base.R.drawable.base_ic_checkmark),
                contentDescription = null,
                tint = DifftTheme.colors.primary,
                modifier = Modifier
                    .size(DifftSettingsDefaults.ChevronSize)
                    .testTag(SERVER_NODE_CHECKMARK_TEST_TAG),
            )
        }
    } else {
        null
    },
) {
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = server.name,
                style = DifftSettingsDefaults.LabelTextStyle,
                color = if (selected) DifftTheme.colors.primary else DifftTheme.colors.textPrimary,
            )
            Spacer(Modifier.width(DifftSettingsDefaults.LabelInfoGap))
            NodeRoleBadge(isPrimary = server.isPrimary)
        }
        Text(
            text = server.domain,
            style = DifftSettingsDefaults.CaptionTextStyle,
            color = DifftTheme.colors.textTertiary,
        )
        Text(
            text = if (server.addrs.isEmpty()) {
                stringResource(R.string.call_server_node_no_ips)
            } else {
                "${stringResource(R.string.call_server_node_ips_label)}: ${server.addrs.joinToString(", ")}"
            },
            style = DifftSettingsDefaults.CaptionTextStyle,
            color = DifftTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun NodeRoleBadge(isPrimary: Boolean) {
    Box(
        modifier = Modifier
            .background(
                if (isPrimary) DifftTheme.colors.primary else DifftTheme.colors.backgroundDisabled,
                DifftSettingsDefaults.GroupShape,
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(
                if (isPrimary) R.string.call_server_node_primary else R.string.call_server_node_fallback
            ),
            style = DifftSettingsDefaults.CaptionTextStyle,
            color = if (isPrimary) DifftTheme.colors.textOnPrimary else DifftTheme.colors.textSecondary,
        )
    }
}

private const val UNKNOWN_NODE_NAME = "----"

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LCallServerNodeContentPreview() {
    val nodes = listOf(
        ServerNode(
            name = "Singapore",
            url = "https://sg.example",
            flag = "🇸🇬",
            region = "sg",
            domain = "sg.example",
            addrs = listOf("1.2.3.4"),
            isPrimary = true,
        ),
        ServerNode(
            name = "Frankfurt",
            url = "https://fra.example",
            flag = "🇩🇪",
            region = "fra",
            domain = "fra.example",
            addrs = emptyList(),
            isPrimary = false,
        ),
    )
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 640.dp)) {
            LCallServerNodeContent(
                servers = nodes,
                upstreamServers = emptyList(),
                showUpstreamSection = true,
                connectedNode = nodes.first(),
                selectedNode = nodes.first(),
                connectionType = CONNECTION_TYPE.HTTP3_QUIC,
                isLoading = false,
                onServerSelected = {},
                onProtocolChange = {},
                onRefresh = {},
            )
        }
    }
}
