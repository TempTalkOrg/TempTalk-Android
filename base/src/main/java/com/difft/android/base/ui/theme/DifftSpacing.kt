package com.difft.android.base.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic spacing system for Difft design system
 * Follows Material Design 3 spacing principles with 4dp grid
 *
 * Naming convention:
 * - inset: Padding inside a component (inner spacing)
 * - stack: Vertical spacing between stacked elements
 * - inline: Horizontal spacing between inline elements
 *
 * Component-specific sizes are also defined for consistency
 */
@Immutable
data class DifftSpacing(
    // ============== Inset (Padding) Spacing ==============
    val insetNone: Dp = 0.dp,
    val insetXXSmall: Dp = 2.dp,
    val insetXSmall: Dp = 4.dp,
    val insetSmall: Dp = 8.dp,
    val insetMedium: Dp = 12.dp,
    val insetLarge: Dp = 16.dp,
    val insetXLarge: Dp = 24.dp,
    val insetXXLarge: Dp = 32.dp,

    // ============== Stack (Vertical) Spacing ==============
    val stackXXSmall: Dp = 2.dp,
    val stackXSmall: Dp = 4.dp,
    val stackSmall: Dp = 8.dp,
    val stackMedium: Dp = 16.dp,
    val stackLarge: Dp = 24.dp,
    val stackXLarge: Dp = 32.dp,
    val stackXXLarge: Dp = 48.dp,

    // ============== Inline (Horizontal) Spacing ==============
    val inlineXXSmall: Dp = 2.dp,
    val inlineXSmall: Dp = 4.dp,
    val inlineSmall: Dp = 8.dp,
    val inlineMedium: Dp = 16.dp,
    val inlineLarge: Dp = 24.dp,
    val inlineXLarge: Dp = 32.dp,
    val inlineXXLarge: Dp = 48.dp,

    // ============== Component Sizes ==============
    // Icon sizes
    val iconXSmall: Dp = 16.dp,
    val iconSmall: Dp = 20.dp,
    val iconMedium: Dp = 24.dp,
    val iconLarge: Dp = 32.dp,
    val iconXLarge: Dp = 48.dp,

    // Avatar sizes
    val avatarXSmall: Dp = 24.dp,
    val avatarSmall: Dp = 32.dp,
    val avatarMedium: Dp = 48.dp,
    val avatarLarge: Dp = 64.dp,
    val avatarXLarge: Dp = 96.dp,
    val avatarXXLarge: Dp = 132.dp,

    // Button sizes
    val buttonHeightSmall: Dp = 32.dp,
    val buttonHeightMedium: Dp = 36.dp,
    val buttonHeightLarge: Dp = 44.dp,
    val buttonHeightXLarge: Dp = 56.dp,

    // Elevation
    val elevationNone: Dp = 0.dp,
    val elevationXSmall: Dp = 1.dp,
    val elevationSmall: Dp = 2.dp,
    val elevationMedium: Dp = 4.dp,
    val elevationLarge: Dp = 8.dp,
    val elevationXLarge: Dp = 16.dp,

    // ============== Structural Sizes ==============
    val topBarHeight: Dp = 56.dp,
    val bottomBarHeight: Dp = 56.dp,
    val listItemHeightSmall: Dp = 48.dp,
    val listItemHeightMedium: Dp = 56.dp,
    val listItemHeightLarge: Dp = 72.dp,

    // ============== Border & Divider ==============
    val borderWidthThin: Dp = 1.dp,
    val borderWidthThick: Dp = 2.dp,
    val dividerThickness: Dp = 1.dp,

    // ============== Touch Target ==============
    val minTouchTarget: Dp = 48.dp,

    // ============== Contact Detail Specific (Legacy Migration) ==============
    val contactDetailAvatarSize: Dp = 132.dp,
    val contactDetailAvatarOffset: Dp = 32.dp,
    val contactDetailIconContainerSize: Dp = 20.dp,
    val contactDetailIconSize: Dp = 12.dp,
    val contactDetailActionButtonSize: Dp = 64.dp,
    val contactDetailShareIconSize: Dp = 24.dp,
    val contactDetailBackIconSize: Dp = 24.dp,
    val contactDetailStatusIconSize: Dp = 16.dp,
    val contactDetailArrowIconSize: Dp = 16.dp,
    val contactDetailInfoLabelWidth: Dp = 100.dp,

    // ============== Common Component Sizes ==============
    val iconContainerSize: Dp = 20.dp,
    val buttonHeight: Dp = 36.dp,
    val dialogWidth: Dp = 231.dp,
    val progressIndicatorStrokeWidth: Dp = 2.dp
)

/**
 * CompositionLocal for DifftSpacing
 */
val LocalDifftSpacing = staticCompositionLocalOf { DifftSpacing() }
