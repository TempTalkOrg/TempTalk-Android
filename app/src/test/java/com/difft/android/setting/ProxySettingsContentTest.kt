package com.difft.android.setting

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.ProbeState
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.UiState
import com.difft.android.network.proxy.ProxyConnectivityChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ProxySettingsContent] contract (issue #1209): the body is stateless, so every branch of the
 * ViewModel's [UiState] can be rendered directly and every affordance reports through a callback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ProxySettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int) = context.getString(resId)

    private val useProxyChanges = mutableListOf<Boolean>()
    private val protectCallChanges = mutableListOf<Boolean>()
    private val addressChanges = mutableListOf<String>()
    private var recheckCount = 0
    private var saveCount = 0

    private fun setContent(state: UiState) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                ProxySettingsContent(
                    state = state,
                    onUseProxyChange = { useProxyChanges += it },
                    onProtectCallIpChange = { protectCallChanges += it },
                    onAddressChange = { addressChanges += it },
                    onRecheck = { recheckCount++ },
                    onSave = { saveCount++ },
                )
            }
        }
    }

    // T18
    @Test
    fun `the default state renders both toggles, the address hint and the save button`() {
        setContent(UiState())

        composeTestRule.onNodeWithText(string(R.string.proxy_use_proxy)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.proxy_protect_call)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.proxy_address_hint)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.proxy_save)).assertExists()
        // No probe has run, so the status group is absent entirely.
        composeTestRule.onNodeWithText(string(R.string.proxy_status_available)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.proxy_status_unavailable)).assertDoesNotExist()
    }

    // T19
    @Test
    fun `a failed probe shows its status and offers a recheck`() {
        setContent(
            UiState(
                probe = ProbeState.ProxyUnavailable(
                    verifyFailed = false,
                    failure = ProxyConnectivityChecker.Failure.UNREACHABLE,
                ),
            )
        )

        composeTestRule.onNodeWithText(string(R.string.proxy_status_unavailable))
            .performScrollTo()
            .assertIsDisplayed()
        // The recheck affordance is icon-only; its label lives in the content description.
        composeTestRule.onNodeWithContentDescription(string(R.string.proxy_recheck))
            .performScrollTo()
            .performClick()
        assertEquals(1, recheckCount)
    }

    // T20
    @Test
    fun `a successful probe offers no recheck`() {
        setContent(UiState(probe = ProbeState.ProxyAvailable))

        composeTestRule.onNodeWithText(string(R.string.proxy_status_available))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.proxy_recheck)).assertDoesNotExist()
    }

    // T21
    @Test
    fun `a probe in flight shows neither status text nor recheck`() {
        setContent(UiState(probe = ProbeState.Checking))

        composeTestRule.onNodeWithText(string(R.string.proxy_status_available)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.proxy_status_unavailable)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.proxy_recheck)).assertDoesNotExist()
    }

    // T22
    @Test
    fun `read-only shows the in-call hint, disables save and blocks editing`() {
        setContent(UiState(readOnly = true, address = "ss://saved", savedAddress = "ss://other"))

        composeTestRule.onNodeWithText(string(R.string.proxy_call_in_progress_hint)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.proxy_save)).assertIsNotEnabled()

        // Read-only, not disabled: the address stays on screen (selectable, copyable) while the
        // field exposes no text-input action at all, so nothing can report an edit.
        composeTestRule.onNodeWithText("ss://saved").assertIsDisplayed()
        val editable = composeTestRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue("a read-only field must expose no set-text action", editable.isEmpty())
        assertTrue("a read-only field must not report edits", addressChanges.isEmpty())
    }

    // T23
    @Test
    fun `the protect-call row stays tappable while the proxy is off`() {
        setContent(UiState(useProxy = false, protectCallIp = false))

        composeTestRule.onAllNodesWithText(string(R.string.proxy_protect_call))
            .filterToOne(hasClickAction())
            .performClick()
        assertEquals(listOf(true), protectCallChanges)
    }

    // T24
    @Test
    fun `typing in the address field reports the new value`() {
        setContent(UiState())

        composeTestRule.onNode(hasSetTextAction()).performTextInput("abc")
        assertEquals(listOf("abc"), addressChanges)
    }

    @Test
    fun `the save button reports a save once it is enabled`() {
        setContent(UiState(address = "ss://new", savedAddress = ""))

        composeTestRule.onNodeWithText(string(R.string.proxy_save)).performClick()
        assertEquals(1, saveCount)
    }
}
