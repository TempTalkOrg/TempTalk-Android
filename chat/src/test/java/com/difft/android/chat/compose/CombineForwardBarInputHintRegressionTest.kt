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

/**
 * T3-12 — regression guard for `chat_message_input_hint`'s *value*.
 *
 * [CombineForwardBar] resolves `R.string.chat_message_input_hint` directly (CombineForwardBar.kt:70)
 * as a bare word-count quantifier ("N/M Message"), unrelated to the E2EE input-placeholder feature.
 * PR-1/Task 3 only ever reads this string as [com.difft.android.chat.ui.ChatMessageViewModel
 * .neutralInputHintRes]'s fallback branch — it never writes to it. This test proves the string's
 * *value* is unaffected by that addition, using the REAL composable (never a re-implementation of
 * its text-concatenation logic).
 */
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

    // T3-12 (en) — chat_message_input_hint value unchanged: "Message"
    @Test
    fun `T3-12 combine forward bar renders 3-of-10 Message in English`() {
        composeTestRule.setContent {
            DifftTheme { CombineForwardBar(stateData = state) }
        }
        composeTestRule.onNodeWithText("3/10 Message").assertExists()
    }

    // T3-12 (zh) — chat_message_input_hint value unchanged: "消息"
    @Config(sdk = [33], qualifiers = "zh")
    @Test
    fun `T3-12 combine forward bar renders 3-of-10 hint in Chinese`() {
        composeTestRule.setContent {
            DifftTheme { CombineForwardBar(stateData = state) }
        }
        composeTestRule.onNodeWithText("3/10 消息").assertExists()
    }
}
