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





@Stable
internal class InputFieldValue(
    val text: String,
    val fieldValue: TextFieldValue,
    val onEdit: (TextFieldValue) -> Unit,
)








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








private fun capToMaxLength(raw: TextFieldValue, maxLength: Int?, previous: TextFieldValue): TextFieldValue {
    if (maxLength == null || raw.text.length <= maxLength) return raw
    val overflow = raw.text.length - maxLength
    val insertEnd = raw.selection.end.coerceIn(0, raw.text.length)
    var trimStart = (insertEnd - overflow).coerceAtLeast(0)
    
    
    if (insertEnd - trimStart != overflow) return previous
    if (trimStart > 0 && raw.text[trimStart - 1].isHighSurrogate() && raw.text[trimStart].isLowSurrogate()) trimStart--
    val text = raw.text.removeRange(trimStart, insertEnd)
    return TextFieldValue(text, TextRange(trimStart.coerceAtMost(text.length)))
}


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
            
            
            
            
            withFrameNanos {}
            focusRequester.requestFocus()
        }
    }
}


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


@Immutable
internal class InputTrailing(
    val showClear: Boolean,
    val reserveSlot: Boolean,
    
    val counterInField: Int?,
    
    val counterBelow: Int?,
)


@Stable
internal class InputChrome(
    
    val boxModifier: Modifier,
    val contentPadding: PaddingValues,
    val clearIconEndInset: Dp,
    val hintColor: Color,
    val leadingIconColor: Color,
    val clearIconTint: Color,
    val layout: FieldLayout,
    val trailing: InputTrailing,
)





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
        clearIconTint = clearIconTint ?: inputClearColor(enabled),
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
    
    
    val counterLimit = maxLength.takeIf { showCounter }
    val counterInField = counterLimit?.takeIf { !multiLine }
    
    
    
    
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







private fun Modifier.inputFieldHeight(height: Dp?, minHeight: Dp, clearMode: ClearMode): Modifier =
    when {
        height != null -> this.height(height)
        clearMode == ClearMode.Never -> this.heightIn(min = minHeight)
        else -> this.heightIn(min = minHeight).height(IntrinsicSize.Min)
    }
