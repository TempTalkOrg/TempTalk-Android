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

    
    private fun fieldNode() = rule.onNode(hasSetTextAction())

    
    @Test
    fun whenNotEmpty_nonEmptyText_showsClearIcon() {
        rule.setContent { Host(value = "a") }
        clearNode().assertExists()
    }

    
    
    
    @Test
    fun whenNotEmpty_emptyText_clearIconDoesNotExist() {
        rule.setContent { Host(value = "") }
        clearNode().assertDoesNotExist()
    }

    
    @Test
    fun whileEditing_notFocused_clearIconDoesNotExist() {
        rule.setContent { Host(value = "a", clearMode = ClearMode.WhileEditing) }
        clearNode().assertDoesNotExist()
    }

    
    @Test
    fun whileEditing_focusedAndNonEmpty_showsClearIcon() {
        rule.setContent { Host(value = "a", clearMode = ClearMode.WhileEditing) }
        fieldNode().performClick()
        rule.waitForIdle()
        clearNode().assertExists()
    }

    
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

    
    @Test
    fun disabled_noClearIconAndFieldDisabled() {
        rule.setContent { Host(value = "a", enabled = false) }
        clearNode().assertDoesNotExist()
        
        fieldNode().assertDoesNotExist()
    }

    
    
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

    
    
    
    @Test
    fun clearIcon_tapTargetIs40dpWideAndContainerHigh() {
        rule.setContent { Host(value = "a") }
        val bounds = clearNode().getBoundsInRoot()
        assertTrue(abs((bounds.width - 40.dp).value) <= 0.5f, "width=${bounds.width}")
        assertTrue(
            abs((bounds.height - DifftTextFieldSize.Large.height).value) <= 0.5f,
            "height=${bounds.height}",
        )
    }

    
    @Test
    fun clearIcon_hasButtonRole() {
        rule.setContent { Host(value = "a") }
        clearNode().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        )
    }

    
    @Test
    fun maxLength_truncatesInsteadOfRejecting() {
        val received = mutableListOf<String>()
        rule.setContent { Host(value = "", maxLength = 5, onValueChange = { received.add(it) }) }
        fieldNode().performTextInput("abcdefgh")
        rule.waitForIdle()
        assertTrue(received.isNotEmpty(), "onValueChange was never called — reject semantics detected")
        assertEquals("abcde", received.last())
    }

    
    @Test
    fun maxLength_dropsWholeSurrogatePairAtTheLimit() {
        val received = mutableListOf<String>()
        rule.setContent { Host(value = "abcd", maxLength = 5, onValueChange = { received.add(it) }) }
        fieldNode().performTextInput("😀")
        rule.waitForIdle()
        val last = received.lastOrNull() ?: "abcd"
        assertEquals("abcd", last)
        assertTrue(last.none { it.isSurrogate() }, "an unpaired surrogate reached the host: $last")
    }
}
