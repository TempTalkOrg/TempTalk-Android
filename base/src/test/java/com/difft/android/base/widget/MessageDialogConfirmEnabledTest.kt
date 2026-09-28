package com.difft.android.base.widget

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `confirmEnabled` contract (issue #1209): a dialog whose content collects a required input must be
 * able to gate its confirm button, and the default must stay enabled for every existing caller.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MessageDialogConfirmEnabledTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // T17
    @Test
    fun `confirmEnabled false disables the button and swallows the tap`() {
        var confirms = 0
        composeTestRule.setContent {
            MessageDialog(
                isVisible = true,
                title = TITLE,
                confirmText = CONFIRM,
                cancelText = CANCEL,
                confirmEnabled = false,
                onConfirm = { confirms++ },
            )
        }

        val button = composeTestRule.onNodeWithText(CONFIRM)
        button.assertIsNotEnabled()
        button.performClick()
        assertEquals(0, confirms)
    }

    @Test
    fun `the default keeps the confirm button enabled`() {
        var confirms = 0
        composeTestRule.setContent {
            MessageDialog(
                isVisible = true,
                title = TITLE,
                confirmText = CONFIRM,
                cancelText = CANCEL,
                onConfirm = { confirms++ },
            )
        }

        val button = composeTestRule.onNodeWithText(CONFIRM)
        button.assertIsEnabled()
        button.performClick()
        assertEquals(1, confirms)
    }

    private companion object {
        const val TITLE = "DialogTitleProbe"
        const val CONFIRM = "ConfirmProbe"
        const val CANCEL = "CancelProbe"
    }
}
