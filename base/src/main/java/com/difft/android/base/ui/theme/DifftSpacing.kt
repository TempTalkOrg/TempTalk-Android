package com.difft.android.base.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic spacing system for the Difft design system.
 *
 * The design library ("Measurements") puts most measurements on an **8 dp grid**, with a
 * **4 dp grid** allowed for smaller elements such as icons and type. 4 dp is the minimum
 * step — there is no 2 dp step in the library, so none is offered here.
 *
 * Naming convention:
 * - inset: padding inside a component (inner spacing)
 * - stack: vertical spacing between stacked elements
 * - inline: horizontal spacing between inline elements
 */
@Immutable
data class DifftSpacing(
    // ============== Inset (Padding) Spacing ==============
    val insetXSmall: Dp = 4.dp,
    val insetSmall: Dp = 8.dp,
    val insetMedium: Dp = 12.dp,
    val insetLarge: Dp = 16.dp,
    val insetXLarge: Dp = 24.dp,
    val insetXXLarge: Dp = 32.dp,

    // ============== Stack (Vertical) Spacing ==============
    val stackXSmall: Dp = 4.dp,
    val stackSmall: Dp = 8.dp,
    val stackMedium: Dp = 16.dp,
    val stackLarge: Dp = 24.dp,
    val stackXLarge: Dp = 32.dp,

    // ============== Inline (Horizontal) Spacing ==============
    val inlineXSmall: Dp = 4.dp,
    val inlineSmall: Dp = 8.dp,
    val inlineMedium: Dp = 16.dp,

    // ============== Component Sizes ==============
    val iconXSmall: Dp = 16.dp,
    val iconMedium: Dp = 24.dp,

    val avatarSmall: Dp = 32.dp,
    val avatarLarge: Dp = 64.dp,

    val elevationMedium: Dp = 4.dp,

    // ============== Border ==============
    val borderWidthThin: Dp = 1.dp,

    // ============== Touch Target ==============
    val minTouchTarget: Dp = 48.dp
)

/**
 * CompositionLocal for DifftSpacing
 */
val LocalDifftSpacing = staticCompositionLocalOf { DifftSpacing() }
