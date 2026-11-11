package com.difft.android.chat.ui.messageaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.difft.android.base.ui.theme.DifftTheme






object MessageActionMenuColors {

    
    val panel: Color
        @Composable @ReadOnlyComposable
        get() = if (DifftTheme.colors.isDark) DifftTheme.colors.backgroundTertiary else DifftTheme.colors.background

    
    val contentDefault: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.textPrimary

    
    val danger: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.error

    
    val pressedPill: Color
        @Composable @ReadOnlyComposable
        get() = if (DifftTheme.colors.isDark) DifftTheme.colors.backgroundQuaternary else DifftTheme.colors.backgroundTertiary

    
    val reactionAddIcon: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.textTertiary

    
    val gridLine: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.dividerFloating
}
