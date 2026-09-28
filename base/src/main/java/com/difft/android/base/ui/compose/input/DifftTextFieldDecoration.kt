package com.difft.android.base.ui.compose.input

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftRadius
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Visual style of [DifftClearableTextField]. Both are the same design-library component
 * (`line-inputsearch`); the style picks which of its two documented skins is drawn.
 */
enum class DifftTextFieldStyle {
    /**
     * Search skin: filled `bg2` box, leading magnifier, radius 8 (Large) / 4 (Medium). No
     * border in any state: the library draws a `primary` outline once focused, but no other
     * client does, so Android keeps the plain filled box.
     */
    Search,

    /**
     * Form skin: `bg1` box with a permanent 1dp border that expresses every state
     * (`line` / `t.info` focused / `error`), radius 4 in both sizes, optional helper text
     * and character counter.
     */
    Form,
}

/** Library size scale. Text and icon sizes are identical in both sizes — only the box grows. */
enum class DifftTextFieldSize(val height: Dp) {
    Medium(32.dp),
    Large(40.dp),
}

/** Shared geometry of the library input component. */
object DifftInputDefaults {
    /** Default box height (`DifftTextFieldSize.Large`). */
    val Height: Dp = DifftTextFieldSize.Large.height
    val HorizontalPadding: Dp = 12.dp

    /** Multi-line fields only; a single-line field centres its text in the box instead. */
    val VerticalPadding: Dp = 10.dp
    val LeadingIconSize: Dp = 14.dp
    val LeadingIconGap: Dp = 4.dp

    /** Reserved trailing slot — the ✕ tap target, wider than the 14dp glyph inside it. */
    val ClearSlotWidth: Dp = 40.dp
    val ClearIconSize: Dp = 14.dp
    val ClearIconEndInset: Dp = HorizontalPadding
    val BorderWidth: Dp = 1.dp

    /** Gap between the box and the helper / error line below it. */
    val SupportingTextGap: Dp = 4.dp

    /** Gap between a multi-line box and its counter, which sits below instead of inside. */
    val CounterBelowGap: Dp = 8.dp
    val MultiLineMinHeight: Dp = 78.dp
}

internal fun DifftTextFieldStyle.radius(size: DifftTextFieldSize): Dp = when (this) {
    DifftTextFieldStyle.Search ->
        if (size == DifftTextFieldSize.Large) DifftRadius.large else DifftRadius.medium
    DifftTextFieldStyle.Form -> DifftRadius.medium
}

/** Resolved box painting: fill, optional border, and the shape both are clipped to. */
@Immutable
internal class InputDecoration(
    val shape: Shape,
    val fill: Color,
    val borderColor: Color?,
)

/**
 * Box painting for the current style and state.
 *
 * An explicit `containerColor` means the host owns the box (it draws its own background and,
 * where it has one, its own border), so the component paints that fill and no border.
 */
@Composable
internal fun inputDecoration(
    style: DifftTextFieldStyle,
    size: DifftTextFieldSize,
    surface: DifftInputSurface,
    containerColor: Color?,
    enabled: Boolean,
    focused: Boolean,
    hasText: Boolean,
    isError: Boolean,
): InputDecoration {
    val colors = DifftTheme.colors
    val shape = RoundedCornerShape(style.radius(size))
    if (containerColor != null) return InputDecoration(shape, containerColor, null)
    return when (style) {
        DifftTextFieldStyle.Search -> InputDecoration(
            shape = shape,
            fill = when (surface) {
                DifftInputSurface.Page -> colors.backgroundSecondary
                DifftInputSurface.Popup -> colors.backgroundTertiary
            },
            borderColor = null,
        )
        DifftTextFieldStyle.Form -> InputDecoration(
            shape = shape,
            fill = if (enabled) colors.background else colors.backgroundSecondary,
            borderColor = when {
                !enabled -> colors.line
                isError -> colors.error
                focused -> colors.textInfo
                else -> colors.line
            },
        )
    }
}

/** Placeholder colour: `t.third` on search, `t.disable` on forms, `bg.disable` when disabled. */
@Composable
internal fun inputHintColor(style: DifftTextFieldStyle, enabled: Boolean): Color = when {
    !enabled && style == DifftTextFieldStyle.Search -> DifftTheme.colors.backgroundDisabled
    style == DifftTextFieldStyle.Search -> DifftTheme.colors.textTertiary
    else -> DifftTheme.colors.textDisabled
}

/** Leading icon and clear glyph colour. */
@Composable
internal fun inputIconColor(enabled: Boolean): Color =
    if (enabled) DifftTheme.colors.textTertiary else DifftTheme.colors.backgroundDisabled

/** Helper / error line, left-aligned to the field. */
@Composable
internal fun InputSupportingText(text: String, isError: Boolean) {
    Text(
        text = text,
        style = DifftTheme.typography.p3,
        color = if (isError) DifftTheme.colors.textError else DifftTheme.colors.textSecondary,
        modifier = Modifier.padding(top = DifftInputDefaults.SupportingTextGap),
    )
}

/** `n/max` counter; turns red past the limit (reachable only when the host caps above [maxLength]). */
@Composable
internal fun InputCounter(
    length: Int,
    maxLength: Int,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "$length/$maxLength",
        style = textStyle,
        color = if (length > maxLength) DifftTheme.colors.textError else DifftTheme.colors.textDisabled,
        modifier = modifier,
    )
}

/**
 * What the box renders under itself: the text-area counter (the single-line counter lives inside
 * the box) and then the helper / error line.
 */
@Composable
internal fun ColumnScope.InputBelowBox(
    trailing: InputTrailing,
    length: Int,
    supportingText: String?,
    isError: Boolean,
) {
    val counterBelow = trailing.counterBelow
    if (counterBelow != null) {
        InputCounter(
            length = length,
            maxLength = counterBelow,
            textStyle = DifftTheme.typography.p4,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = DifftInputDefaults.CounterBelowGap),
        )
    }
    if (supportingText != null) {
        InputSupportingText(text = supportingText, isError = isError)
    }
}

/** Content insets: 12 horizontal, 10 vertical only when the text is top-aligned (multi-line). */
internal fun libraryContentPadding(multiLine: Boolean, hasTrailing: Boolean): PaddingValues {
    val vertical = if (multiLine) DifftInputDefaults.VerticalPadding else 0.dp
    return PaddingValues(
        start = DifftInputDefaults.HorizontalPadding,
        // The trailing slot carries its own end inset; padding here would double it.
        end = if (hasTrailing) 0.dp else DifftInputDefaults.HorizontalPadding,
        top = vertical,
        bottom = vertical,
    )
}
