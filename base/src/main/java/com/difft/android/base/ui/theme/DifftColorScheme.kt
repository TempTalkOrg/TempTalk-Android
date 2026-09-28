package com.difft.android.base.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.difft.android.base.ui.theme.tokens.ColorTokens

/**
 * Extended colors for Difft design system
 * These are custom semantic colors beyond Material Design 3's standard ColorScheme
 */
@Immutable
data class DifftExtendedColors(
    // Functional colors
    val success: Color,
    val warning: Color,

    // Text hierarchy
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val textOnPrimary: Color,
    val textError: Color,
    val textWarning: Color,
    val textSuccess: Color,
    val textInfo: Color,
    val textCaution: Color,

    // Background hierarchy
    val background: Color,
    val bg: Color,    // bg — default page background (settings idiom)
    val backgroundSecondary: Color,  // bg2 (true second elevation, e.g. cards floating above bg1)
    val backgroundTertiary: Color,
    val backgroundQuaternary: Color,  // bg4
    val backgroundDisabled: Color,
    val backgroundPopup: Color,  // bgpopup — popups, bottom sheets, elevated cards
    val backgroundModal: Color,  // bgmodal — dialogs, dropdown menus, notifications
    val backgroundTooltip: Color,
    val backgroundBlue: Color,
    val backgroundSpace: Color,  // bgspace — workspace/brand surface
    val backgroundPagePrimary: Color,  // bgpage-primary
    val backgroundPageSecondary: Color,  // bgpage-secondary
    val bg5: Color,  // bg5 — permanently dark surface (call UI)

    // UI elements
    val line: Color,
    val divider: Color,
    val icon: Color,
    val iconOnPrimary: Color,

    // Special effects
    val ripple: Color,
    val scrim: Color,

    // System UI
    val statusBarColor: Color,
    val navigationBarColor: Color,

    // Theme indicator
    val isDark: Boolean
)

/**
 * Creates Material Design 3 ColorScheme for light theme
 * Maps Difft colors to MD3 semantic roles
 */
fun createLightColorScheme(): ColorScheme = lightColorScheme(
    primary = ColorTokens.Primary,
    onPrimary = ColorTokens.Light.TextOnPrimary,
    primaryContainer = ColorTokens.Light.BackgroundBlue,
    onPrimaryContainer = ColorTokens.Primary,

    secondary = ColorTokens.Light.BackgroundTertiary,
    onSecondary = ColorTokens.Light.TextPrimary,
    secondaryContainer = ColorTokens.Light.Bg,
    onSecondaryContainer = ColorTokens.Light.TextSecondary,

    tertiary = ColorTokens.Light.BackgroundTertiary,
    onTertiary = ColorTokens.Light.TextPrimary,
    tertiaryContainer = ColorTokens.Light.BackgroundTertiary,
    onTertiaryContainer = ColorTokens.Light.TextTertiary,

    error = ColorTokens.Error,
    onError = ColorTokens.Light.TextOnPrimary,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = ColorTokens.ErrorDark,

    background = ColorTokens.Light.Background,
    onBackground = ColorTokens.Light.TextPrimary,

    surface = ColorTokens.Light.BackgroundTertiary,
    onSurface = ColorTokens.Light.TextPrimary,
    surfaceVariant = ColorTokens.Light.BackgroundTertiary,
    onSurfaceVariant = ColorTokens.Light.TextSecondary,

    outline = ColorTokens.Light.Line,
    outlineVariant = ColorTokens.Light.Line,

    scrim = ColorTokens.Light.Scrim,

    inverseSurface = ColorTokens.Dark.BackgroundTertiary,
    inverseOnSurface = ColorTokens.Dark.TextPrimary,
    inversePrimary = ColorTokens.Primary,

    surfaceTint = ColorTokens.Primary
)

/**
 * Creates Material Design 3 ColorScheme for dark theme
 * Maps Difft colors to MD3 semantic roles
 */
fun createDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = ColorTokens.Primary,
    onPrimary = ColorTokens.Dark.TextOnPrimary,
    primaryContainer = ColorTokens.Dark.BackgroundBlue,
    onPrimaryContainer = ColorTokens.InfoLight,

    secondary = ColorTokens.Dark.BackgroundTertiary,
    onSecondary = ColorTokens.Dark.TextPrimary,
    secondaryContainer = ColorTokens.Dark.Bg,
    onSecondaryContainer = ColorTokens.Dark.TextSecondary,

    tertiary = ColorTokens.Dark.BackgroundTertiary,
    onTertiary = ColorTokens.Dark.TextPrimary,
    tertiaryContainer = ColorTokens.Dark.BackgroundTertiary,
    onTertiaryContainer = ColorTokens.Dark.TextTertiary,

    error = ColorTokens.Error,
    onError = ColorTokens.Dark.TextOnPrimary,
    errorContainer = Color(0xFF5F1411),
    onErrorContainer = ColorTokens.Error,

    background = ColorTokens.Dark.Background,
    onBackground = ColorTokens.Dark.TextPrimary,

    surface = ColorTokens.Dark.BackgroundTertiary,
    onSurface = ColorTokens.Dark.TextPrimary,
    surfaceVariant = ColorTokens.Dark.BackgroundTertiary,
    onSurfaceVariant = ColorTokens.Dark.TextSecondary,

    outline = ColorTokens.Dark.Line,
    outlineVariant = ColorTokens.Dark.Line,

    scrim = ColorTokens.Dark.Scrim,

    inverseSurface = ColorTokens.Light.BackgroundTertiary,
    inverseOnSurface = ColorTokens.Light.TextPrimary,
    inversePrimary = ColorTokens.Primary,

    surfaceTint = ColorTokens.Primary
)

/**
 * Creates extended colors for light theme
 */
fun createLightExtendedColors(): DifftExtendedColors = DifftExtendedColors(
    success = ColorTokens.Success,
    warning = ColorTokens.Warning,

    textPrimary = ColorTokens.Light.TextPrimary,
    textSecondary = ColorTokens.Light.TextSecondary,
    textTertiary = ColorTokens.Light.TextTertiary,
    textDisabled = ColorTokens.Light.TextDisabled,
    textOnPrimary = ColorTokens.Light.TextOnPrimary,
    textError = ColorTokens.ErrorDark,
    textWarning = ColorTokens.WarningDark,
    textSuccess = ColorTokens.SuccessDark,
    textInfo = ColorTokens.Info,
    textCaution = ColorTokens.CautionDark,

    background = ColorTokens.Light.Background,
    bg = ColorTokens.Light.Bg,
    backgroundSecondary = ColorTokens.Light.BackgroundSecondary,
    backgroundTertiary = ColorTokens.Light.BackgroundTertiary,
    backgroundQuaternary = ColorTokens.Light.BackgroundQuaternary,
    backgroundDisabled = ColorTokens.Light.BackgroundDisabled,
    backgroundPopup = ColorTokens.Light.BackgroundPopup,
    backgroundModal = ColorTokens.Light.BackgroundModal,
    backgroundTooltip = ColorTokens.Light.Tooltip,
    backgroundBlue = ColorTokens.Light.BackgroundBlue,
    backgroundSpace = ColorTokens.Light.BackgroundSpace,
    backgroundPagePrimary = ColorTokens.Light.BackgroundPagePrimary,
    backgroundPageSecondary = ColorTokens.Light.BackgroundPageSecondary,
    bg5 = ColorTokens.Light.Bg5,

    line = ColorTokens.Light.Line,
    divider = ColorTokens.Light.Divider,
    icon = ColorTokens.Light.Icon,
    iconOnPrimary = ColorTokens.Light.IconOnPrimary,

    ripple = ColorTokens.Light.Ripple,
    scrim = ColorTokens.Light.Scrim,

    statusBarColor = ColorTokens.Light.Background,
    navigationBarColor = ColorTokens.Light.Background,

    isDark = false
)

/**
 * Creates extended colors for dark theme
 */
fun createDarkExtendedColors(): DifftExtendedColors = DifftExtendedColors(
    success = ColorTokens.Success,
    warning = ColorTokens.Warning,

    textPrimary = ColorTokens.Dark.TextPrimary,
    textSecondary = ColorTokens.Dark.TextSecondary,
    textTertiary = ColorTokens.Dark.TextTertiary,
    textDisabled = ColorTokens.Dark.TextDisabled,
    textOnPrimary = ColorTokens.Dark.TextOnPrimary,
    textError = ColorTokens.Error,
    textWarning = ColorTokens.Warning,
    textSuccess = ColorTokens.Success,
    textInfo = ColorTokens.InfoLight,
    textCaution = ColorTokens.Caution,

    background = ColorTokens.Dark.Background,
    bg = ColorTokens.Dark.Bg,
    backgroundSecondary = ColorTokens.Dark.BackgroundSecondary,
    backgroundTertiary = ColorTokens.Dark.BackgroundTertiary,
    backgroundQuaternary = ColorTokens.Dark.BackgroundQuaternary,
    backgroundDisabled = ColorTokens.Dark.BackgroundDisabled,
    backgroundPopup = ColorTokens.Dark.BackgroundPopup,
    backgroundModal = ColorTokens.Dark.BackgroundModal,
    backgroundTooltip = ColorTokens.Dark.Tooltip,
    backgroundBlue = ColorTokens.Dark.BackgroundBlue,
    backgroundSpace = ColorTokens.Dark.BackgroundSpace,
    backgroundPagePrimary = ColorTokens.Dark.BackgroundPagePrimary,
    backgroundPageSecondary = ColorTokens.Dark.BackgroundPageSecondary,
    bg5 = ColorTokens.Dark.Bg5,

    line = ColorTokens.Dark.Line,
    divider = ColorTokens.Dark.Divider,
    icon = ColorTokens.Dark.Icon,
    iconOnPrimary = ColorTokens.Dark.IconOnPrimary,

    ripple = ColorTokens.Dark.Ripple,
    scrim = ColorTokens.Dark.Scrim,

    statusBarColor = ColorTokens.Dark.Background,
    navigationBarColor = ColorTokens.Dark.Background,

    isDark = true
)

/**
 * CompositionLocal for DifftExtendedColors
 */
val LocalDifftExtendedColors = staticCompositionLocalOf { createLightExtendedColors() }
