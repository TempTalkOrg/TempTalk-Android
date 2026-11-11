package com.difft.android.chat.compose

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en")
class ConfidentialTipDialogContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `light confirmation is 48dp and calls confirm only on tap`() = assertConfirmation(false)

    @Test
    fun `dark confirmation is 48dp and calls confirm only on tap`() = assertConfirmation(true)

    private fun assertConfirmation(dark: Boolean) {
        var confirmations = 0
        composeRule.setContent {
            DifftTheme(darkTheme = dark) {
                ConfidentialTipDialogContent("Confidential", "Read once", onConfirm = { confirmations++ })
            }
        }
        assertEquals(0, confirmations)
        composeRule.onNodeWithText("OK").assertHeightIsEqualTo(48.dp).performClick()
        assertEquals(1, confirmations)
    }
}
