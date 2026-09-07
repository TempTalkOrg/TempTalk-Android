package com.difft.android.base.ui.compose.input

import androidx.activity.ComponentActivity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavior pins for [DifftClearableTextField] — the shared clearable input primitive.
 *
 * The highest-value pins guard the migration-wide callback contract: onValueChange and
 * onClear never overlap (tapping ✕ must NOT surface as onValueChange("")), the clear icon
 * is conditionally RENDERED (not alpha-hidden — the legacy alpha=0 button stayed clickable,
 * a phantom tap target), and the reserved trailing slot keeps the text width stable across
 * the empty/non-empty transition.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftClearableTextFieldTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fieldTag = "field"

    @Composable
    private fun Host(
        value: String,
        onValueChange: (String) -> Unit = {},
        onClear: () -> Unit = {},
        clearMode: ClearMode = ClearMode.WhenNotEmpty,
        enabled: Boolean = true,
        maxLength: Int? = null,
        autoFocus: Boolean = false,
        hint: String? = null,
        onImeAction: (() -> Unit)? = null,
        keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions.Default,
        withLeadingIcon: Boolean = false,
        focusRequester: FocusRequester = FocusRequester(),
        interactionSource: MutableInteractionSource = MutableInteractionSource(),
    ) {
        DifftTheme(applyWindowBackground = false) {
            DifftClearableTextField(
                value = value,
                onValueChange = onValueChange,
                onClear = onClear,
                hint = hint,
                clearMode = clearMode,
                leadingIcon = if (withLeadingIcon) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.base_ic_search),
                            contentDescription = null,
                        )
                    }
                } else null,
                enabled = enabled,
                keyboardOptions = keyboardOptions,
                onImeAction = onImeAction,
                maxLength = maxLength,
                autoFocus = autoFocus,
                focusRequester = focusRequester,
                interactionSource = interactionSource,
                modifier = Modifier.testTag(fieldTag),
            )
        }
    }

    private fun clearNode() = rule.onNodeWithContentDescription(
        rule.activity.getString(R.string.base_clear_text)
    )

    /** The inner BasicTextField node (the testTag lands on the outer Row). */
    private fun fieldNode() = rule.onNode(hasSetTextAction())

    // A1: WhenNotEmpty + non-empty -> clear icon exists
    @Test
    fun whenNotEmpty_nonEmptyText_showsClearIcon() {
        rule.setContent { Host(value = "a") }
        clearNode().assertExists()
    }

    // A2: WhenNotEmpty + empty -> node DOES NOT EXIST (phantom-tap-target pin).
    // assertIsNotDisplayed()/assertIsNotEnabled() would be too weak: the legacy alpha=0
    // button was exactly "not displayed but clickable".
    @Test
    fun whenNotEmpty_emptyText_clearIconDoesNotExist() {
        rule.setContent { Host(value = "") }
        clearNode().assertDoesNotExist()
    }

    // A3: WhileEditing + non-empty but NOT focused -> hidden
    @Test
    fun whileEditing_notFocused_clearIconDoesNotExist() {
        rule.setContent { Host(value = "a", clearMode = ClearMode.WhileEditing) }
        clearNode().assertDoesNotExist()
    }

    // A4: WhileEditing + focused + non-empty -> shown
    @Test
    fun whileEditing_focusedAndNonEmpty_showsClearIcon() {
        rule.setContent { Host(value = "a", clearMode = ClearMode.WhileEditing) }
        fieldNode().performClick()
        rule.waitForIdle()
        clearNode().assertExists()
    }

    // A5: tapping ✕ fires onClear exactly once and onValueChange zero times
    @Test
    fun tappingClear_firesOnClearOnly() {
        var clears = 0
        var changes = 0
        rule.setContent { Host(value = "a", onValueChange = { changes++ }, onClear = { clears++ }) }
        clearNode().performClick()
        rule.waitForIdle()
        assertEquals(1, clears)
        assertEquals(0, changes)
    }

    // A6: clear refocuses the input (X2: pre-assert not focused before the tap)
    @Test
    fun tappingClear_refocusesInput() {
        lateinit var clearFocus: () -> Unit
        rule.setContent {
            val focusManager = LocalFocusManager.current
            clearFocus = { focusManager.clearFocus(force = true) }
            Host(value = "a")
        }
        rule.runOnUiThread { clearFocus() }
        rule.waitForIdle()
        fieldNode().assertIsNotFocused()
        clearNode().performClick()
        rule.waitForIdle()
        fieldNode().assertIsFocused()
    }

    // A7: typing fires onValueChange only
    @Test
    fun typing_firesOnValueChangeOnly() {
        var clears = 0
        val received = mutableListOf<String>()
        rule.setContent { Host(value = "", onValueChange = { received.add(it) }, onClear = { clears++ }) }
        fieldNode().performTextInput("x")
        rule.waitForIdle()
        assertEquals(listOf("x"), received)
        assertEquals(0, clears)
    }

    // A8/A9: autoFocus
    @Test
    fun autoFocusTrue_focusesOnFirstComposition() {
        rule.setContent { Host(value = "", autoFocus = true) }
        rule.waitForIdle()
        fieldNode().assertIsFocused()
    }

    @Test
    fun autoFocusFalse_staysUnfocused() {
        rule.setContent { Host(value = "") }
        rule.waitForIdle()
        fieldNode().assertIsNotFocused()
    }

    // A10: reserved trailing slot -> field width identical for empty vs non-empty (R2 pin:
    // no 40dp text reflow on the first keystroke)
    @Test
    fun whenNotEmpty_slotReserved_textAreaWidthStableAcrossEmptyTransition() {
        val state = androidx.compose.runtime.mutableStateOf("")
        rule.setContent { Host(value = state.value, onValueChange = { state.value = it }) }
        val emptyWidth = fieldNode().getBoundsInRoot().width
        rule.runOnUiThread { state.value = "a" }
        rule.waitForIdle()
        val filledWidth = fieldNode().getBoundsInRoot().width
        assertTrue(abs((emptyWidth - filledWidth).value) <= 0.5f, "text area width changed: $emptyWidth -> $filledWidth")
    }

    // A11: IME action handler fires
    @Test
    fun imeAction_firesHandler() {
        var actions = 0
        rule.setContent {
            Host(
                value = "a",
                onImeAction = { actions++ },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
            )
        }
        fieldNode().performClick()
        fieldNode().performImeAction()
        assertEquals(1, actions)
    }

    // A12: disabled -> no clear icon, field not enabled
    @Test
    fun disabled_noClearIconAndFieldDisabled() {
        rule.setContent { Host(value = "a", enabled = false) }
        clearNode().assertDoesNotExist()
        // A disabled BasicTextField exposes no SetText semantics at all — its absence is the pin.
        fieldNode().assertDoesNotExist()
    }

    // A13: WhileEditing — tapping ✕ completes even though it would hide itself if the tap
    // stole focus (focusProperties { canFocus = false } pin)
    @Test
    fun whileEditing_tappingClear_isNotInterruptedByFocusTransfer() {
        var clears = 0
        rule.setContent { Host(value = "a", clearMode = ClearMode.WhileEditing, onClear = { clears++ }) }
        rule.onNodeWithTag(fieldTag).performClick()
        rule.waitForIdle()
        clearNode().performClick()
        rule.waitForIdle()
        assertEquals(1, clears)
    }

    // A14: hint shows only when empty
    @Test
    fun hint_visibleOnlyWhenEmpty() {
        rule.setContent { Host(value = "", hint = "SearchHintProbe") }
        rule.onNodeWithText("SearchHintProbe").assertExists()
    }

    @Test
    fun hint_hiddenWhenNonEmpty() {
        rule.setContent { Host(value = "a", hint = "SearchHintProbe") }
        rule.onNodeWithText("SearchHintProbe").assertDoesNotExist()
    }

    // A16: clear tap target is the full 40dp-wide slot at container height (user-ratified
    // 40dp; pin against a later "wrap_content" shrink)
    @Test
    fun clearIcon_tapTargetIs40dpWideAndContainerHigh() {
        rule.setContent { Host(value = "a") }
        val bounds = clearNode().getBoundsInRoot()
        assertTrue(abs((bounds.width - 40.dp).value) <= 0.5f, "width=${bounds.width}")
        assertTrue(abs((bounds.height - 36.dp).value) <= 0.5f, "height=${bounds.height}")
    }

    // A17: a11y semantics — ✕ has Role.Button
    @Test
    fun clearIcon_hasButtonRole() {
        rule.setContent { Host(value = "a") }
        clearNode().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        )
    }

    // T-F5: maxLength TRUNCATES (InputFilter.LengthFilter semantics), never rejects
    @Test
    fun maxLength_truncatesInsteadOfRejecting() {
        val received = mutableListOf<String>()
        rule.setContent { Host(value = "", maxLength = 5, onValueChange = { received.add(it) }) }
        fieldNode().performTextInput("abcdefgh")
        rule.waitForIdle()
        assertTrue(received.isNotEmpty(), "onValueChange was never called — reject semantics detected")
        assertEquals("abcde", received.last())
    }
}
