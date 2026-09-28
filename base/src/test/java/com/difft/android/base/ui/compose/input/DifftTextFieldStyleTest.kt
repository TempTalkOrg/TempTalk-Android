package com.difft.android.base.ui.compose.input

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import com.difft.android.base.R
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
 * Pins for the design-library skins of [DifftClearableTextField]: the two box heights, the
 * helper line and the character counter (which owns the trailing slot, so the ✕ steps aside).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftTextFieldStyleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tag = "field"

    private fun clearNode() = rule.onNodeWithContentDescription(
        rule.activity.getString(R.string.base_clear_text)
    )

    // S1: the default box is the library Large size.
    @Test
    fun defaultSize_isLarge40() {
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = "",
                    onValueChange = {},
                    onClear = {},
                    modifier = Modifier.testTag(tag),
                )
            }
        }
        val height = rule.onNodeWithTag(tag).getBoundsInRoot().height
        assertTrue(abs((height - DifftTextFieldSize.Large.height).value) <= 0.5f, "height=$height")
    }

    // S2: Medium is the 32dp box, in both skins.
    @Test
    fun mediumSize_searchBoxIs32() = assertMediumHeight(DifftTextFieldStyle.Search)

    @Test
    fun mediumSize_formBoxIs32() = assertMediumHeight(DifftTextFieldStyle.Form)

    private fun assertMediumHeight(style: DifftTextFieldStyle) {
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = "",
                    onValueChange = {},
                    onClear = {},
                    style = style,
                    size = DifftTextFieldSize.Medium,
                    modifier = Modifier.testTag(tag),
                )
            }
        }
        val height = rule.onNodeWithTag(tag).getBoundsInRoot().height
        assertTrue(abs((height - DifftTextFieldSize.Medium.height).value) <= 0.5f, "$style height=$height")
    }

    // S3: the helper / error line renders under the field.
    @Test
    fun supportingText_isRendered() {
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = "",
                    onValueChange = {},
                    onClear = {},
                    style = DifftTextFieldStyle.Form,
                    isError = true,
                    supportingText = "SupportingProbe",
                )
            }
        }
        rule.onNodeWithText("SupportingProbe").assertExists()
    }

    // S4: the counter reads n/max and follows the text.
    @Test
    fun counter_tracksLength() {
        val state = mutableStateOf("")
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = state.value,
                    onValueChange = { state.value = it },
                    onClear = {},
                    style = DifftTextFieldStyle.Form,
                    showCounter = true,
                    maxLength = 20,
                )
            }
        }
        rule.onNodeWithText("0/20").assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("abc")
        rule.waitForIdle()
        rule.onNodeWithText("3/20").assertExists()
    }

    // S5: the counter replaces the clear icon (library "Basic + word count" draws no ✕).
    @Test
    fun counter_replacesClearIcon() {
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = "abc",
                    onValueChange = {},
                    onClear = {},
                    style = DifftTextFieldStyle.Form,
                    showCounter = true,
                    maxLength = 20,
                )
            }
        }
        clearNode().assertDoesNotExist()
    }

    // S7: a multi-line form field is at least the library text-area minimum tall.
    @Test
    fun multiLineForm_usesTextAreaMinHeight() {
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = "",
                    onValueChange = {},
                    onClear = {},
                    style = DifftTextFieldStyle.Form,
                    clearMode = ClearMode.Never,
                    maxLines = 4,
                    height = null,
                    modifier = Modifier.testTag(tag),
                )
            }
        }
        val height = rule.onNodeWithTag(tag).getBoundsInRoot().height
        assertTrue(
            height.value >= DifftInputDefaults.MultiLineMinHeight.value - 0.5f,
            "height=$height",
        )
    }

    // S6: showCounter without a maxLength renders nothing extra and keeps the ✕.
    @Test
    fun counterWithoutMaxLength_keepsClearIcon() {
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftClearableTextField(
                    value = "abc",
                    onValueChange = {},
                    onClear = {},
                    style = DifftTextFieldStyle.Form,
                    showCounter = true,
                )
            }
        }
        clearNode().assertExists()
    }
}
