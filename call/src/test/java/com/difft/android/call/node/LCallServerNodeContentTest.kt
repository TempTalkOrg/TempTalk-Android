package com.difft.android.call.node

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.ServerNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [LCallServerNodeContent] contract (issue #1209): the selected node is now marked visually, the
 * protocol row toggles the transport, and the upstream section is gated on its own flag.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LCallServerNodeContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int) = context.getString(resId)

    private val nodeA = ServerNode(
        name = "NodeAlpha",
        url = "https://a.example",
        flag = "A",
        region = "aa",
        domain = "a.example",
        addrs = listOf("1.1.1.1"),
        isPrimary = true,
    )
    private val nodeB = ServerNode(
        name = "NodeBravo",
        url = "https://b.example",
        flag = "B",
        region = "bb",
        domain = "b.example",
        addrs = emptyList(),
        isPrimary = false,
    )

    private fun setContent(
        servers: List<ServerNode> = listOf(nodeA, nodeB),
        upstreamServers: List<ServerNode> = emptyList(),
        showUpstreamSection: Boolean = false,
        connectedNode: ServerNode? = nodeA,
        selectedNode: ServerNode? = nodeA,
        connectionType: CONNECTION_TYPE = CONNECTION_TYPE.HTTP3_QUIC,
        isLoading: Boolean = false,
        onServerSelected: (ServerNode) -> Unit = {},
        onProtocolChange: (Boolean) -> Unit = {},
        onRefresh: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                LCallServerNodeContent(
                    servers = servers,
                    upstreamServers = upstreamServers,
                    showUpstreamSection = showUpstreamSection,
                    connectedNode = connectedNode,
                    selectedNode = selectedNode,
                    connectionType = connectionType,
                    isLoading = isLoading,
                    onServerSelected = onServerSelected,
                    onProtocolChange = onProtocolChange,
                    onRefresh = onRefresh,
                )
            }
        }
    }

    // T28
    @Test
    fun `only the selected node carries a checkmark`() {
        setContent(selectedNode = nodeB)

        val marks = composeTestRule
            .onAllNodesWithTag(SERVER_NODE_CHECKMARK_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("exactly one node should be marked", 1, marks.size)
        composeTestRule.onNodeWithText(nodeB.name).assertIsDisplayed()
    }

    // T29
    @Test
    fun `tapping a node reports it`() {
        var picked: ServerNode? = null
        setContent(onServerSelected = { picked = it })

        // The node name also appears in the connection-status row, so pick the clickable one.
        composeTestRule.onAllNodesWithText(nodeA.name)
            .filterToOne(hasClickAction())
            .performClick()
        assertEquals(nodeA, picked)
    }

    // T30
    @Test
    fun `the protocol row reads WebSocket and requests QUIC when toggled`() {
        var requested: Boolean? = null
        setContent(connectionType = CONNECTION_TYPE.WEB_SOCKET, onProtocolChange = { requested = it })

        composeTestRule.onNodeWithText("WebSocket").assertIsDisplayed()
        composeTestRule.onNodeWithText("WebSocket").performClick()
        assertEquals(true, requested)
    }

    // T31
    @Test
    fun `a disconnected page shows the disconnected status`() {
        setContent(connectedNode = null)

        composeTestRule.onNodeWithText(string(R.string.call_server_node_disconnected)).assertExists()
    }

    // T32
    @Test
    fun `the upstream section is gated and shows an empty state`() {
        setContent(showUpstreamSection = false)
        composeTestRule.onNodeWithText(string(R.string.call_server_node_upstream_title))
            .assertDoesNotExist()
    }

    @Test
    fun `an enabled but empty upstream section shows its empty state`() {
        setContent(showUpstreamSection = true, upstreamServers = emptyList())

        composeTestRule.onNodeWithText(string(R.string.call_server_node_upstream_title)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.call_server_node_upstream_empty)).assertExists()
    }

    // T33
    @Test
    fun `loading replaces the refresh action with a spinner`() {
        setContent(isLoading = true)

        composeTestRule.onNodeWithTag(SERVER_NODE_LOADING_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(SERVER_NODE_REFRESH_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `the refresh action reports a refresh`() {
        var refreshes = 0
        setContent(isLoading = false, onRefresh = { refreshes++ })

        composeTestRule.onNodeWithTag(SERVER_NODE_REFRESH_TEST_TAG).performClick()
        assertEquals(1, refreshes)
    }
}
