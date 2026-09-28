package com.difft.android.base.ui.compose.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Editing state of the field: the host's [text] plus the [TextFieldValue] the editor actually
 * runs on. See [rememberInputFieldValue] for why the two differ.
 */
@Stable
internal class InputFieldValue(
    val text: String,
    val fieldValue: TextFieldValue,
    val onEdit: (TextFieldValue) -> Unit,
)

/**
 * TextFieldValue internally so a programmatic `value` change places the cursor at the end —
 * the legacy setText(x) + setSelection(x.length) idiom every edit/prefill flow relied on.
 * User edits flow through unchanged. lastEmitted distinguishes a genuine external set from
 * an async host echoing our own edit back one frame late (a ViewModel-fed value): resetting
 * on the echo would drop in-flight keystrokes and snap the caret to the end.
 */
@Composable
internal fun rememberInputFieldValue(
    value: String,
    maxLength: Int?,
    onValueChange: (String) -> Unit,
): InputFieldValue {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var lastEmitted by remember { mutableStateOf<String?>(null) }
    if (fieldValue.text != value && value != lastEmitted) {
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    return InputFieldValue(
        text = value,
        fieldValue = fieldValue,
        // Truncate (never reject) on overflow — matches XML InputFilter.LengthFilter paste
        // semantics.
        onEdit = { raw ->
            val capped = capToMaxLength(raw, maxLength, fieldValue)
            fieldValue = capped
            if (capped.text != value) {
                lastEmitted = capped.text
                onValueChange(capped.text)
            }
        },
    )
}

/**
 * InputFilter.LengthFilter parity: on overflow, trim the tail of the INSERTED run (which ends at
 * the new selection) instead of the end of the whole string — an insert at the start of a full
 * field must not silently delete trailing characters.
 */
private fun capToMaxLength(raw: TextFieldValue, maxLength: Int?, previous: TextFieldValue): TextFieldValue {
    if (maxLength == null || raw.text.length <= maxLength) return raw
    val overflow = raw.text.length - maxLength
    val insertEnd = raw.selection.end.coerceIn(0, raw.text.length)
    val trimStart = (insertEnd - overflow).coerceAtLeast(0)
    // If the edit shape is unexpected (selection not at the insert end), fall back to
    // rejecting the edit outright — still never corrupts existing text.
    if (insertEnd - trimStart != overflow) return previous
    val text = raw.text.removeRange(trimStart, insertEnd)
    return TextFieldValue(text, TextRange(trimStart.coerceAtMost(text.length)))
}

/** Focus plumbing: host notification and the optional open-with-the-keyboard behaviour. */
@Composable
internal fun InputFocusEffects(
    isFocused: Boolean,
    onFocusChanged: ((Boolean) -> Unit)?,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
) {
    if (onFocusChanged != null) {
        LaunchedEffect(isFocused) { onFocusChanged(isFocused) }
    }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            // Wait one frame so the field is placed before focus: focus-driven autofill/IME
            // callbacks report the node's bounds, and requesting focus during the first
            // composition (pre-placement) anchored the system autofill popup at the screen
            // origin instead of on the field.
            withFrameNanos {}
            focusRequester.requestFocus()
        }
    }
}

/** Pass-through editor configuration; nothing here affects the box's chrome. */
@Stable
internal class InputEditorSpec(
    val textStyle: TextStyle,
    val contentColor: Color,
    val enabled: Boolean = true,
    val readOnly: Boolean = false,
    val maxLines: Int = 1,
    val visualTransformation: VisualTransformation = VisualTransformation.None,
    val keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    val onImeAction: (() -> Unit)? = null,
    val contentType: ContentType? = null,
)

/**
 * Vertical layout of the field's content, decided by `maxLines`.
 *
 * Single line: content centred, natural height. Multi-line is a text area: content and hint anchor
 * to the top so an empty field taller than one line does not float its hint in the middle.
 */
@Immutable
internal class FieldLayout(
    val multiLine: Boolean,
    val rowAlignment: Alignment.Vertical,
    val boxAlignment: Alignment,
)

private fun fieldLayout(maxLines: Int): FieldLayout =
    if (maxLines > 1) {
        FieldLayout(multiLine = true, Alignment.Top, Alignment.TopStart)
    } else {
        FieldLayout(multiLine = false, Alignment.CenterVertically, Alignment.CenterStart)
    }

/** What occupies the trailing end of the box, and what the box renders under itself. */
@Immutable
internal class InputTrailing(
    val showClear: Boolean,
    val reserveSlot: Boolean,
    /** Non-null when the counter sits inside the box (single line). */
    val counterInField: Int?,
    /** Non-null when the counter sits under the box (text area). */
    val counterBelow: Int?,
)

/** Everything the field's chrome is made of, derived once per composition. */
@Stable
internal class InputChrome(
    /** Width, height rule, clip, fill and border of the box, ready to apply to the field row. */
    val boxModifier: Modifier,
    val contentPadding: PaddingValues,
    val clearIconEndInset: Dp,
    val hintColor: Color,
    val leadingIconColor: Color,
    val clearIconTint: Color,
    val layout: FieldLayout,
    val trailing: InputTrailing,
)

/**
 * Resolves the style/size/state inputs into the concrete chrome the field draws. Defaults mirror
 * the [DifftClearableTextField] parameter defaults, so a call may name only what it changes.
 */
@Composable
internal fun inputChrome(
    style: DifftTextFieldStyle,
    size: DifftTextFieldSize,
    surface: DifftInputSurface,
    clearMode: ClearMode,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    focused: Boolean = false,
    hasText: Boolean = false,
    isError: Boolean = false,
    maxLines: Int = 1,
    showCounter: Boolean = false,
    maxLength: Int? = null,
    containerColor: Color? = null,
    hintColor: Color? = null,
    clearIconTint: Color? = null,
    height: Dp? = null,
    minHeight: Dp? = null,
    contentPadding: PaddingValues? = null,
    clearIconEndInset: Dp = DifftInputDefaults.ClearIconEndInset,
): InputChrome {
    val layout = fieldLayout(maxLines)
    val trailing = inputTrailing(
        clearMode = clearMode, enabled = enabled, readOnly = readOnly, focused = focused,
        hasText = hasText, multiLine = layout.multiLine, showCounter = showCounter,
        maxLength = maxLength,
    )
    val decoration = inputDecoration(
        style = style,
        size = size,
        surface = surface,
        containerColor = containerColor,
        enabled = enabled,
        focused = focused,
        hasText = hasText,
        isError = isError,
    )
    val resolvedMinHeight = minHeight
        ?: if (layout.multiLine && style == DifftTextFieldStyle.Form) {
            DifftInputDefaults.MultiLineMinHeight
        } else {
            0.dp
        }
    return InputChrome(
        boxModifier = Modifier
            .fillMaxWidth()
            .inputFieldHeight(height = height, minHeight = resolvedMinHeight, clearMode = clearMode)
            .clip(decoration.shape)
            .background(decoration.fill)
            .then(
                decoration.borderColor?.let {
                    Modifier.border(DifftInputDefaults.BorderWidth, it, decoration.shape)
                } ?: Modifier
            ),
        contentPadding = contentPadding ?: libraryContentPadding(
            multiLine = layout.multiLine,
            hasTrailing = trailing.reserveSlot || trailing.counterInField != null,
        ),
        clearIconEndInset = clearIconEndInset,
        hintColor = hintColor ?: inputHintColor(style, enabled),
        leadingIconColor = inputIconColor(enabled),
        clearIconTint = clearIconTint ?: inputIconColor(enabled),
        layout = layout,
        trailing = trailing,
    )
}

private fun inputTrailing(
    clearMode: ClearMode,
    enabled: Boolean,
    readOnly: Boolean,
    focused: Boolean,
    hasText: Boolean,
    multiLine: Boolean,
    showCounter: Boolean,
    maxLength: Int?,
): InputTrailing {
    // A counter takes over the trailing slot; single-line fields show it in place of the ✕,
    // multi-line ones move it below the box (library "Long input").
    val counterLimit = maxLength.takeIf { showCounter }
    val counterInField = counterLimit?.takeIf { !multiLine }
    // Slot reservation and icon rendering are deliberately separate rules: search boxes keep the
    // 40dp trailing slot permanently (legacy paddingEnd was unconditional — avoids a 40dp text
    // reflow on the first keystroke); form fields reserve it only while focused so the read-only
    // display state stays pixel-identical to the pre-migration EditText.
    val slotMode = counterInField == null
    return InputTrailing(
        showClear = slotMode && enabled && !readOnly && when (clearMode) {
            ClearMode.WhenNotEmpty -> hasText
            ClearMode.WhileEditing -> hasText && focused
            ClearMode.Never -> false
        },
        reserveSlot = slotMode && when (clearMode) {
            ClearMode.WhenNotEmpty -> true
            ClearMode.WhileEditing -> focused
            ClearMode.Never -> false
        },
        counterInField = counterInField,
        counterBelow = counterLimit?.takeIf { multiLine },
    )
}

/**
 * Height rule for the field's outer row. Without a fixed height the row must be bounded to its
 * content's intrinsic height, or the clear slot's `fillMaxHeight` expands the whole field to the
 * incoming max constraint (a focused form field grew to full screen). With no clear slot there is
 * no such child, so the row may wrap and a multi-line field can grow.
 */
private fun Modifier.inputFieldHeight(height: Dp?, minHeight: Dp, clearMode: ClearMode): Modifier =
    when {
        height != null -> this.height(height)
        clearMode == ClearMode.Never -> this.heightIn(min = minHeight)
        else -> this.heightIn(min = minHeight).height(IntrinsicSize.Min)
    }
