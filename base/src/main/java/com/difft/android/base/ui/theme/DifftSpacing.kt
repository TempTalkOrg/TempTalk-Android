package com.difft.android.base.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp













@Immutable
data class DifftSpacing(
    
    val insetXSmall: Dp = 4.dp,
    val insetSmall: Dp = 8.dp,
    val insetMedium: Dp = 12.dp,
    val insetLarge: Dp = 16.dp,
    val insetXLarge: Dp = 24.dp,
    val insetXXLarge: Dp = 32.dp,

    
    val stackXSmall: Dp = 4.dp,
    val stackSmall: Dp = 8.dp,
    val stackMedium: Dp = 16.dp,
    val stackLarge: Dp = 24.dp,
    val stackXLarge: Dp = 32.dp,

    
    val inlineXSmall: Dp = 4.dp,
    val inlineSmall: Dp = 8.dp,
    val inlineMedium: Dp = 16.dp,

    
    val iconXSmall: Dp = 16.dp,
    val iconMedium: Dp = 24.dp,

    val avatarSmall: Dp = 32.dp,
    val avatarLarge: Dp = 64.dp,

    val elevationMedium: Dp = 4.dp,

    
    val borderWidthThin: Dp = 1.dp,

    
    val minTouchTarget: Dp = 48.dp
)




val LocalDifftSpacing = staticCompositionLocalOf { DifftSpacing() }
