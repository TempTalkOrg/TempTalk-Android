package com.difft.android.setting

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.R
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * About page after folding its hand-rolled rows into the settings kit (issue #1209): the three
 * read-only rows carry no chevron, each action row carries one, and the call-network entry is gated.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AboutPageViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int) = context.getString(resId)

    private var updateClicks = 0

    private fun setPage(showCallServerEntry: Boolean) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                AboutPageView(
                    onBackClick = {},
                    appVersion = "2.4.2",
                    channelLabel = "Google",
                    buildVersion = "883521",
                    buildTime = "12:00 PM",
                    onCheckForUpdateClick = { updateClicks++ },
                    joinDesktopClick = {},
                    callServerUrlNodeClick = {},
                    showCallServerEntry = showCallServerEntry,
                )
            }
        }
    }

    @Test
    fun `value rows render their value and action rows carry a chevron`() {
        setPage(showCallServerEntry = false)

        composeTestRule.onNodeWithText("2.4.2 (Google)").assertIsDisplayed()
        composeTestRule.onNodeWithText("883521").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_version)).assertIsDisplayed()

        // Three clickable rows (check update, desktop, submit log) -> three chevrons; the three
        // read-only rows contribute none.
        val chevrons = composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.ChevronTestTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(3, chevrons.size)

        composeTestRule.onNodeWithText(string(R.string.settings_check_new_version)).performClick()
        assertEquals(1, updateClicks)
    }

    @Test
    fun `the call-network entry appears only when enabled`() {
        setPage(showCallServerEntry = true)

        composeTestRule
            .onNodeWithText(string(com.difft.android.call.R.string.call_service_node_dashboard_title))
            .assertIsDisplayed()
        val chevrons = composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.ChevronTestTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(4, chevrons.size)
    }
}
