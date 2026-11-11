package com.difft.android.chat.compose

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode





@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class CombineForwardBarInputHintRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val state = SelectMessageState(
        editModel = true,
        selectedMessageIds = setOf("1", "2", "3"),
        totalMessageCount = 10
    )

    @Test
    fun `T3-12 combine forward bar renders 3-of-10 Message in English`() {
        composeTestRule.setContent {
            DifftTheme { CombineForwardBar(stateData = state) }
        }
        composeTestRule.onNodeWithText("3/10 Message").assertExists()
    }

    @Config(sdk = [33], qualifiers = "zh")
    @Test
    fun `T3-12 combine forward bar renders 3-of-10 hint in Chinese`() {
        composeTestRule.setContent {
            DifftTheme { CombineForwardBar(stateData = state) }
        }
        composeTestRule.onNodeWithText("3/10 消息").assertExists()
    }
}
