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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme

/** Clear-icon visibility semantics for [DifftClearableTextField]. */
enum class ClearMode {
    /** Search semantics: the clear icon shows whenever the text is non-empty. */
    WhenNotEmpty,

    /** Form semantics: the clear icon shows only while focused AND non-empty (iOS `.whileEditing`). */
    WhileEditing,

    /** Plain input: never shows the clear icon and never reserves its trailing slot. */
    Never,
}

/**
 * Surface the field sits on; picks the default fill so every search / input field on a given
 * surface looks the same.
 */
enum class DifftInputSurface {
    /** Page background (`bg` / `bg1`): `bg2` fill. */
    Page,

    /**
     * Popup / bottom-sheet background (`bg.popup`): `bg3` fill. One step above the surface, the
     * same relationship [Page] has on a page — `bg2` would be invisible here because it equals
     * `bg.popup` in dark mode.
     */
    Popup,
}

/**
 * Clearable text input — the one implementation behind every search box and short-form text
 * field in the app. Single line by default; [maxLines] > 1 is supported only together with
 * [ClearMode.Never], because the clear slot pins the row to its content's intrinsic height.
 *
 * [style] picks the design-library skin ([DifftTextFieldStyle.Search] filled box,
 * [DifftTextFieldStyle.Form] outlined box with helper text and counter) and [size] its height.
 *
 * Fully stateless: [value] is owned by the host; this composable never caches, trims,
 * debounces, or derives from it.
 *
 * ## Callback contract
 * [onValueChange] and [onClear] never overlap:
 * - user typing   -> [onValueChange] only
 * - user taps ✕   -> [onClear] only, NEVER `onValueChange("")`
 *
 * [onClear] is therefore the complete "query was cleared" notification. The host's
 * handler must itself perform whatever side effects a TextWatcher receiving an empty
 * string used to perform (reset lists, cancel in-flight work, restore initial state).
 *
 * ## Host state contract
 * [value] must be backed by synchronous host state (a View field or `remember`/
 * `rememberSaveable`). Routing it through an async ViewModel StateFlow round-trip causes
 * dropped characters and cursor jumps (known String-BasicTextField behavior).
 */
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
    /** Form style only: paints the border `error` and turns [supportingText] red. */
    isError: Boolean = false,
    /** Helper / error line rendered below the field, left-aligned to it. */
    supportingText: String? = null,
    /** Renders `n/max` (requires [maxLength]) instead of the clear icon. */
    showCounter: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onImeAction: (() -> Unit)? = null,
    contentType: ContentType? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    /** Masking (e.g. `PasswordVisualTransformation()`); passed straight to the text field. */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** Focusable and selectable but not editable — the "view-only" state that keeps copy working. */
    readOnly: Boolean = false,
    /** > 1 requires [ClearMode.Never]; see the class doc. */
    maxLines: Int = 1,
    /** Fill of the [DifftTextFieldStyle.Search] skin; forms always sit on `bg1`. */
    surface: DifftInputSurface = DifftInputSurface.Page,
    /**
     * Explicit fill. Setting it means the host owns the box: the component paints this colour
     * and draws no border of its own.
     */
    containerColor: Color? = null,
    contentColor: Color = DifftTheme.colors.textPrimary,
    /** Placeholder colour; null follows [style]. */
    hintColor: Color? = null,
    /** Clear-glyph colour; null follows [style]. */
    clearIconTint: Color? = null,
    textStyle: TextStyle = DifftTheme.typography.p3,
    /** Fixed box height; null lets the field wrap its content (text areas). */
    height: Dp? = size.height,
    /** Null uses 0, or the library's 78dp text-area minimum for a multi-line form. */
    minHeight: Dp? = null,
    /** Null uses the library insets: 12 horizontal, plus 10 vertical when multi-line. */
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

    // Column so the helper line and the text-area counter can sit under the box; with neither
    // present it wraps the box exactly, so a caller's modifier still measures the field itself.
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

/**
 * The box itself: chrome, leading icon, hint, editor and the trailing slot (clear ✕ or counter).
 */
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
                    // Clear-and-refocus: keep the user in typing flow.
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
        // Run the handler, then the platform default (legacy editor-action listeners
        // returned false, so Done still hid the keyboard).
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
            // Autofill content type: without it the platform's suggestion/autofill popup has no
            // virtual-node bounds to anchor to and shows at the screen origin instead of on the
            // field.
            .let { m ->
                editor.contentType?.let { ct -> m.semantics { this.contentType = ct } } ?: m
            },
    )
}

/**
 * A multi-line field is taller than its text, so the editor alone would leave the lower part of
 * the visible box dead to taps. The box itself hands taps on that blank area to the editor; taps on
 * the editor never reach here, so the caret still lands where the finger is.
 */
private fun Modifier.focusOnTap(focusRequester: FocusRequester): Modifier =
    this.clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }

/** The reserved trailing slot; the glyph inside it renders only while [showClear]. */
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
        // Conditional rendering: when absent the node does not exist, so the legacy
        // "alpha=0 but still clickable" phantom tap target cannot recur.
        if (showClear) {
            ClearSlot(
                clearIconTint = clearIconTint,
                clearIconEndInset = clearIconEndInset,
                onClick = onClick,
            )
        }
    }
}

/**
 * The hint stays composed and only fades out: hint Text and the text field resolve line height
 * differently on device, so removing the hint on the first character made the whole field's
 * height jump. Keeping it in the measure pass pins the height across the empty/filled
 * transition. Semantics are cleared while hidden so TalkBack never announces an invisible hint.
 */
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
        // The whole slot is the tap target (40dp x container height); the glyph inside stays
        // small. Role/label live here so TalkBack sees one button.
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
            // Keep the ✕ out of the focus system: clickable is focusable by default, and under
            // WhileEditing a focus transfer would hide the ✕ before its own click completes.
            // TalkBack still reaches it via semantics (Role.Button + contentDescription).
            .focusProperties { canFocus = false }
            .semantics { contentDescription = clearLabel }
            .padding(end = clearIconEndInset),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            painter = painterResource(R.drawable.base_ic_clear_filled),
            contentDescription = null,
            tint = clearIconTint,
            modifier = Modifier.size(DifftInputDefaults.ClearIconSize),
        )
    }
}
