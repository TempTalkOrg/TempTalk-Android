package com.difft.android.base.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.tokens.Palette


object DifftContactsTokens {
    val cardAvatar: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.avatarLarge
    val groupCardAvatar = 72.dp
    val actionSize = 64.dp
    val actionCell = 64.dp
    val cardMaxWidth = 600.dp
    val cardPadding: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.insetLarge
    val cardRadius: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.radius.floating
    val cardRowMinHeight = 36.dp
    val cardLabelWidth = 72.dp
    
    val cardSidePadding = 20.dp
    val cardIcon: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.iconMedium
    val bannerRadius: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.radius.card
    val bannerIcon: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.iconXSmall
    val tileWidth = 80.dp
    val tileAvatar: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.avatarLarge
    val tileMaskFraction = 0.36f
    val tileBlur: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.insetSmall
    val edgeFade: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.insetXLarge
    val homeHeaderHeight = 52.dp
    val rowHeight = 72.dp
    val rowAvatar = 52.dp
    val rowTextStart = 80.dp
    val rowEndInset: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.insetLarge
    val divider = 0.5.dp
    val rowTimerIcon = 12.dp
    val sectionHeight = 32.dp
    val indexWidth: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.iconMedium
    val indexLetterHeight: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.insetLarge
    val tabHeight = 32.dp
    val tabHorizontalPadding = 14.dp
    val tabBandPadding: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.insetLarge
    val tabBorder = 1.dp
    
    val countHeight = 14.dp
    val countPadding = 5.dp
    val countBorder = 0.5.dp
    val zero = 0.dp
    val gapSmall: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.inlineXSmall
    val gapMedium: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.inlineSmall
    val gapLarge: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.insetMedium
    val gapSection: androidx.compose.ui.unit.Dp
        @Composable @ReadOnlyComposable
        get() = DifftTheme.spacing.stackMedium
    val disabledAlpha = 0.4f
    val section = TextStyle(fontSize = 14.sp, lineHeight = 16.sp)
    val countdown = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    val countdownUrgent = countdown.copy(fontWeight = FontWeight.Bold)

    val countStyle = TextStyle(fontSize = 9.sp, lineHeight = 9.sp)

    val countBackground: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.primary
    val countForeground: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.onPrimary
    val countBorderColor = Palette.Gray0
    val countdownTint = Color.Black.copy(alpha = 0.42f)
    val countdownUrgentTint = Color.Black.copy(alpha = 0.55f)
    val countdownForeground = Palette.Gray0
    val transparent = Color.Transparent
}
