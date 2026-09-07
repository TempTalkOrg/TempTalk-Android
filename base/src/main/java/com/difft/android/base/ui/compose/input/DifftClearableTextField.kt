package com.difft.android.base.ui.compose.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

@Composable
private fun inputSurfaceColor(surface: DifftInputSurface, containerColor: Color?): Color =
    containerColor ?: when (surface) {
        DifftInputSurface.Page -> DifftTheme.colors.backgroundSecondary
        DifftInputSurface.Popup -> DifftTheme.colors.backgroundTertiary
    }

/** Shared geometry defaults — values mirror the legacy XML search-input declarations. */
object DifftInputDefaults {
    val Height: Dp = 36.dp                 // iOS OWSSearchBar baseline
    val CornerRadius: Dp = 8.dp
    val ContentPaddingStart: Dp = 10.dp
    val LeadingIconGap: Dp = 6.dp
    val LeadingIconWidth: Dp = 15.dp
    val LeadingIconHeight: Dp = 14.dp
    val ClearSlotWidth: Dp = 40.dp         // reserved trailing slot (== legacy paddingEnd)
    val ClearIconSize: Dp = 16.dp          // small refined glyph (iOS parity); tap target stays the 40dp slot
    val ClearIconEndInset: Dp = 10.dp      // == ContentPaddingStart, so both sides read identically
}

/**
 * Single-line clearable text input — the one implementation behind every search box and
 * short-form text field in the app.
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
    clearMode: ClearMode = ClearMode.WhenNotEmpty,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onImeAction: (() -> Unit)? = null,
    contentType: ContentType? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    surface: DifftInputSurface = DifftInputSurface.Page,
    /** Explicit fill; null uses the [surface] default. */
    containerColor: Color? = null,
    contentColor: Color = DifftTheme.colors.textPrimary,
    hintColor: Color = DifftTheme.colors.textDisabled,
    clearIconTint: Color = DifftTheme.colors.textSecondary,
    textStyle: TextStyle = DifftTheme.typography.bodyMedium,
    height: Dp? = DifftInputDefaults.Height,
    minHeight: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(start = DifftInputDefaults.ContentPaddingStart),
    clearIconEndInset: Dp = DifftInputDefaults.ClearIconEndInset,
    maxLength: Int? = null,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    if (onFocusChanged != null) {
        LaunchedEffect(isFocused) { onFocusChanged(isFocused) }
    }

    // TextFieldValue internally so a programmatic [value] change places the cursor at the end —
    // the legacy setText(x) + setSelection(x.length) idiom every edit/prefill flow relied on.
    // User edits flow through unchanged. lastEmitted distinguishes a genuine external set from
    // an async host echoing our own edit back one frame late (a ViewModel-fed value): resetting
    // on the echo would drop in-flight keystrokes and snap the caret to the end.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var lastEmitted by remember { mutableStateOf<String?>(null) }
    if (fieldValue.text != value && value != lastEmitted) {
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }

    val showClear = enabled && when (clearMode) {
        ClearMode.WhenNotEmpty -> value.isNotEmpty()
        ClearMode.WhileEditing -> value.isNotEmpty() && isFocused
        ClearMode.Never -> false
    }
    // Slot reservation and icon rendering are deliberately separate rules: search boxes keep the
    // 40dp trailing slot permanently (legacy paddingEnd was unconditional — avoids a 40dp text
    // reflow on the first keystroke); form fields reserve it only while focused so the read-only
    // display state stays pixel-identical to the pre-migration EditText.
    val reserveSlot = when (clearMode) {
        ClearMode.WhenNotEmpty -> true
        ClearMode.WhileEditing -> isFocused
        ClearMode.Never -> false
    }

    val cursorColor = DifftTheme.colors.primary

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

    Row(
        modifier = modifier
            // With no fixed height, bound the row to its content's intrinsic height — otherwise
            // the trailing slot's fillMaxHeight would expand the whole field to the incoming max
            // constraint (a focused form field grew to full screen).
            .let { if (height != null) it.height(height) else it.heightIn(min = minHeight).height(IntrinsicSize.Min) }
            .clip(RoundedCornerShape(DifftInputDefaults.CornerRadius))
            .background(inputSurfaceColor(surface, containerColor)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(DifftInputDefaults.LeadingIconGap))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (hint != null) {
                    HintText(hint = hint, visible = value.isEmpty(), textStyle = textStyle, hintColor = hintColor)
                }
                BasicTextField(
                    value = fieldValue,
                    // Truncate (never reject) on overflow — matches XML InputFilter.LengthFilter
                    // paste semantics.
                    onValueChange = { raw ->
                        val capped = capToMaxLength(raw, maxLength, fieldValue)
                        fieldValue = capped
                        if (capped.text != value) {
                            lastEmitted = capped.text
                            onValueChange(capped.text)
                        }
                    },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = textStyle.copy(color = contentColor),
                    cursorBrush = SolidColor(cursorColor),
                    keyboardOptions = keyboardOptions,
                    // Run the handler, then the platform default (legacy editor-action listeners
                    // returned false, so Done still hid the keyboard).
                    keyboardActions = onImeAction?.let { action ->
                        KeyboardActions {
                            action()
                            defaultKeyboardAction(keyboardOptions.imeAction)
                        }
                    } ?: KeyboardActions.Default,
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // Autofill content type: without it the platform's suggestion/autofill
                        // popup has no virtual-node bounds to anchor to and shows at the screen
                        // origin instead of on the field.
                        .let { m ->
                            contentType?.let { ct -> m.semantics { this.contentType = ct } } ?: m
                        },
                )
            }
        }

        if (reserveSlot) {
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
                        onClick = {
                            onClear()
                            // Clear-and-refocus: keep the user in typing flow.
                            runCatching { focusRequester.requestFocus() }
                        },
                    )
                }
            }
        }
    }
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
