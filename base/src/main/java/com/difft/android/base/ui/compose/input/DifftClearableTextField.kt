package com.difft.android.base.ui.compose.input

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme


enum class ClearMode {
    
    WhenNotEmpty,

    
    WhileEditing,

    
    Never,
}





enum class DifftInputSurface {
    
    Page,

    




    Popup,
}

























@Composable
fun DifftClearableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    style: DifftTextFieldStyle = DifftTextFieldStyle.Form,
    size: DifftTextFieldSize = DifftTextFieldSize.Large,
    clearMode: ClearMode = ClearMode.WhenNotEmpty,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    
    isError: Boolean = false,
    
    supportingText: String? = null,
    
    showCounter: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onImeAction: (() -> Unit)? = null,
    contentType: ContentType? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    
    visualTransformation: VisualTransformation = VisualTransformation.None,
    
    readOnly: Boolean = false,
    
    maxLines: Int = 1,
    
    surface: DifftInputSurface = DifftInputSurface.Page,
    



    containerColor: Color? = null,
    contentColor: Color = DifftTheme.colors.textPrimary,
    
    hintColor: Color? = null,
    
    clearIconTint: Color? = null,
    textStyle: TextStyle = DifftTheme.typography.p3,
    
    height: Dp? = size.height,
    
    minHeight: Dp? = null,
    
    contentPadding: PaddingValues? = null,
    clearIconEndInset: Dp = DifftInputDefaults.ClearIconEndInset,
    maxLength: Int? = null,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    InputFocusEffects(
        isFocused = isFocused,
        onFocusChanged = onFocusChanged,
        autoFocus = autoFocus,
        focusRequester = focusRequester,
    )
    val field = rememberInputFieldValue(value, maxLength, onValueChange)
    val chrome = inputChrome(
        style = style, size = size, surface = surface, clearMode = clearMode,
        enabled = enabled, readOnly = readOnly, focused = isFocused, hasText = value.isNotEmpty(),
        isError = isError, maxLines = maxLines, showCounter = showCounter, maxLength = maxLength,
        containerColor = containerColor, hintColor = hintColor, clearIconTint = clearIconTint,
        height = height, minHeight = minHeight, contentPadding = contentPadding,
        clearIconEndInset = clearIconEndInset,
    )
    val editor = InputEditorSpec(
        textStyle = textStyle, contentColor = contentColor, enabled = enabled, readOnly = readOnly,
        maxLines = maxLines, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, onImeAction = onImeAction, contentType = contentType,
    )

    
    
    Column(modifier = modifier) {
        FieldBox(
            field = field,
            chrome = chrome,
            editor = editor,
            hint = hint,
            leadingIcon = leadingIcon,
            focusRequester = focusRequester,
            interactionSource = interactionSource,
            onClear = onClear,
        )
        InputBelowBox(
            trailing = chrome.trailing,
            length = value.length,
            supportingText = supportingText,
            isError = isError,
        )
    }
}




@Composable
private fun FieldBox(
    field: InputFieldValue,
    chrome: InputChrome,
    editor: InputEditorSpec,
    hint: String?,
    leadingIcon: (@Composable () -> Unit)?,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
    onClear: () -> Unit,
) {
    val layout = chrome.layout
    Row(
        modifier = chrome.boxModifier
            .then(
                if (layout.multiLine && editor.enabled) Modifier.focusOnTap(focusRequester) else Modifier
            ),
        verticalAlignment = layout.rowAlignment,
    ) {
        Row(
            Modifier.weight(1f).padding(chrome.contentPadding),
            verticalAlignment = layout.rowAlignment,
        ) {
            if (leadingIcon != null) {
                CompositionLocalProvider(LocalContentColor provides chrome.leadingIconColor) {
                    leadingIcon()
                }
                Spacer(Modifier.width(DifftInputDefaults.LeadingIconGap))
            }
            Box(Modifier.weight(1f), contentAlignment = layout.boxAlignment) {
                if (hint != null) {
                    HintText(
                        hint = hint,
                        visible = field.text.isEmpty(),
                        textStyle = editor.textStyle,
                        hintColor = chrome.hintColor,
                    )
                }
                FieldEditor(
                    field = field,
                    editor = editor,
                    focusRequester = focusRequester,
                    interactionSource = interactionSource,
                )
            }
        }

        val counterInField = chrome.trailing.counterInField
        if (counterInField != null) {
            InputCounter(
                length = field.text.length,
                maxLength = counterInField,
                textStyle = DifftTheme.typography.p3,
                modifier = Modifier.padding(end = DifftInputDefaults.HorizontalPadding),
            )
        } else if (chrome.trailing.reserveSlot) {
            ClearSlotBox(
                showClear = chrome.trailing.showClear,
                clearIconTint = chrome.clearIconTint,
                clearIconEndInset = chrome.clearIconEndInset,
                onClick = {
                    onClear()
                    
                    runCatching { focusRequester.requestFocus() }
                },
            )
        }
    }
}

@Composable
private fun FieldEditor(
    field: InputFieldValue,
    editor: InputEditorSpec,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
) {
    val cursorColor = DifftTheme.colors.primary
    BasicTextField(
        value = field.fieldValue,
        onValueChange = field.onEdit,
        enabled = editor.enabled,
        readOnly = editor.readOnly,
        singleLine = editor.maxLines == 1,
        maxLines = editor.maxLines,
        visualTransformation = editor.visualTransformation,
        textStyle = editor.textStyle.copy(color = editor.contentColor),
        cursorBrush = SolidColor(cursorColor),
        keyboardOptions = editor.keyboardOptions,
        
        keyboardActions = editor.onImeAction?.let { action ->
            KeyboardActions {
                action()
                defaultKeyboardAction(editor.keyboardOptions.imeAction)
            }
        } ?: KeyboardActions.Default,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            
            
            
            .let { m ->
                editor.contentType?.let { ct -> m.semantics { this.contentType = ct } } ?: m
            },
    )
}






private fun Modifier.focusOnTap(focusRequester: FocusRequester): Modifier =
    this.clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }


@Composable
private fun ClearSlotBox(
    showClear: Boolean,
    clearIconTint: Color,
    clearIconEndInset: Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(DifftInputDefaults.ClearSlotWidth)
            .fillMaxHeight(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        
        if (showClear) {
            ClearSlot(
                clearIconTint = clearIconTint,
                clearIconEndInset = clearIconEndInset,
                onClick = onClick,
            )
        }
    }
}






@Composable
private fun HintText(hint: String, visible: Boolean, textStyle: TextStyle, hintColor: Color) {
    Text(
        text = hint,
        style = textStyle,
        color = hintColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .alpha(if (visible) 1f else 0f)
            .let { if (visible) it else it.clearAndSetSemantics {} },
    )
}

@Composable
private fun ClearSlot(
    clearIconTint: Color,
    clearIconEndInset: Dp,
    onClick: () -> Unit,
) {
    val clearLabel = stringResource(R.string.base_clear_text)
    Box(
        
        
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(
                    bounded = false,
                    radius = DifftInputDefaults.ClearIconSize,
                ),
                role = Role.Button,
                onClick = onClick,
            )
            
            
            
            .focusProperties { canFocus = false }
            .semantics { contentDescription = clearLabel }
            .padding(end = clearIconEndInset),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            painter = painterResource(R.drawable.base_ic_clear_filled),
            contentDescription = null,
            tint = clearIconTint,
            
            
            modifier = Modifier
                .layout { measurable, _ ->
                    val size = DifftInputDefaults.ClearIconSize.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(size, size))
                    layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
                },
        )
    }
}
