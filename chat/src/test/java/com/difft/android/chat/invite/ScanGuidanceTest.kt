package com.difft.android.chat.invite

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The scanner's bottom block: the caller's guidance card and the declined-code banner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScanGuidanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the guidance card and the declined-code banner both show`() {
        composeTestRule.setContent {
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                ScanGuidance(note = "Open Quicall on your computer.", notice = "This code is for a phone signing in.")
            }
        }
        composeTestRule.onNodeWithText("Open Quicall on your computer.").assertIsDisplayed()
        composeTestRule.onNodeWithText("This code is for a phone signing in.").assertIsDisplayed()
    }

    @Test
    fun `the caller's two guidance lines become one paragraph`() {
        assertEquals("Do this. On one network.", scanNote("Do this.", "On one network."))
        assertEquals("Do this.", scanNote("Do this.", null))
        assertNull(scanNote(null, ""))
        // Chinese sentences run together after a full-width stop.
        assertEquals("在电脑上打开 Quicall。请连接同一 Wi-Fi。", scanNote("在电脑上打开 Quicall。", "请连接同一 Wi-Fi。"))
    }
}
