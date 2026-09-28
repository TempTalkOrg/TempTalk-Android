package com.difft.android.base.ui.compose.input

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Multi-line contract (issue #1209): with `clearMode = Never` and no fixed height the field wraps
 * its content, so a value long enough to wrap grows the field — bounded by `maxLines`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftClearableTextFieldMultiLineTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun multiLineFieldGrowsWithWrappedTextAndStopsAtMaxLines() {
        val state = mutableStateOf("a")
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = state.value,
                    onValueChange = { state.value = it },
                    onClear = { state.value = "" },
                    clearMode = ClearMode.Never,
                    maxLines = MAX_LINES,
                    height = null,
                    minHeight = 0.dp,
                    modifier = Modifier.width(FIELD_WIDTH).testTag(TAG),
                )
            }
        }
        val oneLine = rule.onNodeWithTag(TAG).getBoundsInRoot().height

        // Long enough to wrap past three lines in a 200dp-wide field.
        rule.runOnUiThread { state.value = List(60) { "wrap" }.joinToString(" ") }
        rule.waitForIdle()
        val wrapped = rule.onNodeWithTag(TAG).getBoundsInRoot()

        assertTrue(
            wrapped.height.value > oneLine.value,
            "wrapped text should grow the field: $oneLine -> ${wrapped.height}",
        )
        assertTrue(
            wrapped.height.value < oneLine.value * (MAX_LINES + 1),
            "maxLines should cap growth below ${MAX_LINES + 1} lines, measured ${wrapped.height}",
        )
        assertTrue(
            wrapped.right - wrapped.left == FIELD_WIDTH,
            "the field must keep its declared width, measured ${wrapped.right - wrapped.left}",
        )
    }

    private companion object {
        const val TAG = "multiline_field"
        const val HINT = "hint"
        const val MAX_LINES = 3
        val FIELD_WIDTH = 200.dp
    }

    @Test
    fun multiLineHintAnchorsToTopNotCenter() {
        rule.setContent {
            DifftTheme {
                DifftClearableTextField(
                    value = "",
                    onValueChange = {},
                    onClear = {},
                    hint = HINT,
                    clearMode = ClearMode.Never,
                    maxLines = MAX_LINES,
                    height = null,
                    minHeight = 120.dp,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        val field = rule.onNodeWithTag(TAG).getBoundsInRoot()
        val hint = rule.onNodeWithText(HINT, useUnmergedTree = true).getBoundsInRoot()
        val centerGap = (field.top + field.height / 2) - hint.top
        val topGap = hint.top - field.top
        assertTrue(
            topGap.value < centerGap.value,
            "multi-line hint should sit near the top: field=$field hint=$hint",
        )
    }

    @Test
    fun tapOnBlankAreaOfMultiLineFieldFocusesTheEditor() {
        rule.setContent {
            DifftTheme {
                DifftClearableTextField(
                    value = "",
                    onValueChange = {},
                    onClear = {},
                    hint = HINT,
                    clearMode = ClearMode.Never,
                    maxLines = MAX_LINES,
                    height = null,
                    minHeight = 120.dp,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        rule.onNodeWithTag(TAG).performTouchInput { click(bottomCenter.copy(y = bottomCenter.y - 4f)) }
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }
}
