package com.difft.android.base.ui.compose.input

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Height-parity pin for the intrinsic-height (form) variant: the field's height must not
 * change across empty/focused/filled states. Regression source: the hint Text and the text
 * field resolve line height differently on device, so conditionally REMOVING the hint on the
 * first character made the field jump — the hint now stays composed (alpha-hidden). The hidden
 * hint clears its semantics, so it must be unfindable by text while the field is filled.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftClearableTextFieldHeightParityTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun formVariant_heightStableAcrossEmptyFocusFilledStates() {
        val state = mutableStateOf("")
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = state.value,
                    onValueChange = { state.value = it },
                    onClear = { state.value = "" },
                    hint = "HeightHintProbe",
                    clearMode = ClearMode.WhileEditing,
                    height = null,
                    modifier = Modifier.testTag("f"),
                )
            }
        }
        val emptyUnfocused = rule.onNodeWithTag("f").getBoundsInRoot().height
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitForIdle()
        val emptyFocused = rule.onNodeWithTag("f").getBoundsInRoot().height
        rule.runOnUiThread { state.value = "a" }
        rule.waitForIdle()
        val filledFocused = rule.onNodeWithTag("f").getBoundsInRoot().height

        assertTrue(
            abs((emptyUnfocused - emptyFocused).value) <= 0.5f &&
                abs((emptyFocused - filledFocused).value) <= 0.5f,
            "height jitter: $emptyUnfocused -> $emptyFocused -> $filledFocused",
        )
        // The alpha-hidden hint must be semantically invisible while filled.
        rule.onNodeWithText("HeightHintProbe").assertDoesNotExist()
    }
}
