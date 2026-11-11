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





enum class DifftTextFieldStyle {
    




    Search,

    




    Form,
}


enum class DifftTextFieldSize(val height: Dp) {
    Medium(32.dp),
    Large(40.dp),
}


object DifftInputDefaults {
    
    val Height: Dp = DifftTextFieldSize.Large.height
    val HorizontalPadding: Dp = 12.dp

    
    val VerticalPadding: Dp = 10.dp
    val LeadingIconSize: Dp = 14.dp
    val LeadingIconGap: Dp = 4.dp

    
    val ClearSlotWidth: Dp = 40.dp

    
    val ClearIconSize: Dp = 17.dp
    val ClearIconEndInset: Dp = HorizontalPadding
    val BorderWidth: Dp = 1.dp

    
    val SupportingTextGap: Dp = 4.dp

    
    val CounterBelowGap: Dp = 8.dp
    val MultiLineMinHeight: Dp = 78.dp
}

internal fun DifftTextFieldStyle.radius(size: DifftTextFieldSize): Dp = when (this) {
    DifftTextFieldStyle.Search ->
        if (size == DifftTextFieldSize.Large) DifftRadius.large else DifftRadius.medium
    DifftTextFieldStyle.Form -> DifftRadius.medium
}


@Immutable
internal class InputDecoration(
    val shape: Shape,
    val fill: Color,
    val borderColor: Color?,
)







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
                focused -> colors.primary
                else -> colors.line
            },
        )
    }
}


@Composable
internal fun inputHintColor(style: DifftTextFieldStyle, enabled: Boolean): Color = when {
    !enabled && style == DifftTextFieldStyle.Search -> DifftTheme.colors.backgroundDisabled
    style == DifftTextFieldStyle.Search -> DifftTheme.colors.textTertiary
    else -> DifftTheme.colors.textDisabled
}


@Composable
internal fun inputIconColor(enabled: Boolean): Color =
    if (enabled) DifftTheme.colors.textTertiary else DifftTheme.colors.backgroundDisabled


@Composable
internal fun inputClearColor(enabled: Boolean): Color =
    if (enabled) DifftTheme.colors.icon else DifftTheme.colors.backgroundDisabled


@Composable
internal fun InputSupportingText(text: String, isError: Boolean) {
    Text(
        text = text,
        style = DifftTheme.typography.p3,
        color = if (isError) DifftTheme.colors.textError else DifftTheme.colors.textSecondary,
        modifier = Modifier.padding(top = DifftInputDefaults.SupportingTextGap),
    )
}


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


internal fun libraryContentPadding(multiLine: Boolean, hasTrailing: Boolean): PaddingValues {
    val vertical = if (multiLine) DifftInputDefaults.VerticalPadding else 0.dp
    return PaddingValues(
        start = DifftInputDefaults.HorizontalPadding,
        
        end = if (hasTrailing) 0.dp else DifftInputDefaults.HorizontalPadding,
        top = vertical,
        bottom = vertical,
    )
}
