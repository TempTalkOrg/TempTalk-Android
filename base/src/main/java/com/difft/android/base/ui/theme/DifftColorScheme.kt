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
    val successDark: Color,
    val warning: Color,
    val warningDark: Color,
    val info: Color,

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

    // Background hierarchy
    val background: Color,
    val backgroundSecondary: Color,
    val backgroundTertiary: Color,
    val backgroundDisabled: Color,
    val backgroundModal: Color,
    val backgroundPopup: Color,
    val backgroundTooltip: Color,
    val backgroundBlue: Color,
    val backgroundSettingItem: Color,  // Corresponds to bg.setting.item

    // UI elements
    val divider: Color,
    val border: Color,
    val icon: Color,
    val iconOnPrimary: Color,

    // Special effects
    val ripple: Color,
    val overlay: Color,
    val scrim: Color,

    // Component specific
    val buttonPrimary: Color,
    val buttonSecondary: Color,
    val buttonDisabled: Color,

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
    secondaryContainer = ColorTokens.Light.BackgroundSecondary,
    onSecondaryContainer = ColorTokens.Light.TextSecondary,

    tertiary = ColorTokens.Light.Surface,
    onTertiary = ColorTokens.Light.TextPrimary,
    tertiaryContainer = ColorTokens.Light.BackgroundTertiary,
    onTertiaryContainer = ColorTokens.Light.TextTertiary,

    error = ColorTokens.Error,
    onError = ColorTokens.Light.TextOnPrimary,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = ColorTokens.ErrorDark,

    background = ColorTokens.Light.Background,
    onBackground = ColorTokens.Light.TextPrimary,

    surface = ColorTokens.Light.Surface,
    onSurface = ColorTokens.Light.TextPrimary,
    surfaceVariant = ColorTokens.Light.SurfaceVariant,
    onSurfaceVariant = ColorTokens.Light.TextSecondary,

    outline = ColorTokens.Light.Border,
    outlineVariant = ColorTokens.Light.Divider,

    scrim = ColorTokens.Light.Scrim,

    inverseSurface = ColorTokens.Dark.Surface,
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
    secondaryContainer = ColorTokens.Dark.BackgroundSecondary,
    onSecondaryContainer = ColorTokens.Dark.TextSecondary,

    tertiary = ColorTokens.Dark.Surface,
    onTertiary = ColorTokens.Dark.TextPrimary,
    tertiaryContainer = ColorTokens.Dark.BackgroundTertiary,
    onTertiaryContainer = ColorTokens.Dark.TextTertiary,

    error = ColorTokens.Error,
    onError = ColorTokens.Dark.TextOnPrimary,
    errorContainer = Color(0xFF5F1411),
    onErrorContainer = ColorTokens.Error,

    background = ColorTokens.Dark.Background,
    onBackground = ColorTokens.Dark.TextPrimary,

    surface = ColorTokens.Dark.Surface,
    onSurface = ColorTokens.Dark.TextPrimary,
    surfaceVariant = ColorTokens.Dark.SurfaceVariant,
    onSurfaceVariant = ColorTokens.Dark.TextSecondary,

    outline = ColorTokens.Dark.Border,
    outlineVariant = ColorTokens.Dark.Divider,

    scrim = ColorTokens.Dark.Scrim,

    inverseSurface = ColorTokens.Light.Surface,
    inverseOnSurface = ColorTokens.Light.TextPrimary,
    inversePrimary = ColorTokens.Primary,

    surfaceTint = ColorTokens.Primary
)

/**
 * Creates extended colors for light theme
 */
fun createLightExtendedColors(): DifftExtendedColors = DifftExtendedColors(
    success = ColorTokens.Success,
    successDark = ColorTokens.SuccessDark,
    warning = ColorTokens.Warning,
    warningDark = ColorTokens.WarningDark,
    info = ColorTokens.Info,

    textPrimary = ColorTokens.Light.TextPrimary,
    textSecondary = ColorTokens.Light.TextSecondary,
    textTertiary = ColorTokens.Light.TextTertiary,
    textDisabled = ColorTokens.Light.TextDisabled,
    textOnPrimary = ColorTokens.Light.TextOnPrimary,
    textError = ColorTokens.ErrorDark,
    textWarning = ColorTokens.WarningDark,
    textSuccess = ColorTokens.SuccessDark,
    textInfo = ColorTokens.Info,

    background = ColorTokens.Light.Background,
    backgroundSecondary = ColorTokens.Light.BackgroundSecondary,
    backgroundTertiary = ColorTokens.Light.BackgroundTertiary,
    backgroundDisabled = ColorTokens.Light.BackgroundDisabled,
    backgroundModal = ColorTokens.Light.Background,
    backgroundPopup = ColorTokens.Light.Background,
    backgroundTooltip = ColorTokens.Light.Tooltip,
    backgroundBlue = ColorTokens.Light.BackgroundBlue,
    backgroundSettingItem = ColorTokens.Light.BackgroundSettingItem,

    divider = ColorTokens.Light.Divider,
    border = ColorTokens.Light.Border,
    icon = ColorTokens.Light.Icon,
    iconOnPrimary = ColorTokens.Light.IconOnPrimary,

    ripple = ColorTokens.Light.Ripple,
    overlay = ColorTokens.Light.Overlay,
    scrim = ColorTokens.Light.Scrim,

    buttonPrimary = ColorTokens.Primary,
    buttonSecondary = ColorTokens.Light.Background,
    buttonDisabled = ColorTokens.Light.BackgroundDisabled,

    statusBarColor = ColorTokens.Light.Background,
    navigationBarColor = ColorTokens.Light.Background,

    isDark = false
)

/**
 * Creates extended colors for dark theme
 */
fun createDarkExtendedColors(): DifftExtendedColors = DifftExtendedColors(
    success = ColorTokens.Success,
    successDark = ColorTokens.Success,
    warning = ColorTokens.Warning,
    warningDark = ColorTokens.Warning,
    info = ColorTokens.InfoLight,

    textPrimary = ColorTokens.Dark.TextPrimary,
    textSecondary = ColorTokens.Dark.TextSecondary,
    textTertiary = ColorTokens.Dark.TextTertiary,
    textDisabled = ColorTokens.Dark.TextDisabled,
    textOnPrimary = ColorTokens.Dark.TextOnPrimary,
    textError = ColorTokens.Error,
    textWarning = ColorTokens.Warning,
    textSuccess = ColorTokens.Success,
    textInfo = ColorTokens.InfoLight,

    background = ColorTokens.Dark.Background,
    backgroundSecondary = ColorTokens.Dark.BackgroundSecondary,
    backgroundTertiary = ColorTokens.Dark.BackgroundTertiary,
    backgroundDisabled = ColorTokens.Dark.BackgroundDisabled,
    backgroundModal = ColorTokens.Dark.BackgroundTertiary,
    backgroundPopup = ColorTokens.Dark.BackgroundSecondary,
    backgroundTooltip = ColorTokens.Dark.Tooltip,
    backgroundBlue = ColorTokens.Dark.BackgroundBlue,
    backgroundSettingItem = ColorTokens.Dark.BackgroundSettingItem,

    divider = ColorTokens.Dark.Divider,
    border = ColorTokens.Dark.Border,
    icon = ColorTokens.Dark.Icon,
    iconOnPrimary = ColorTokens.Dark.IconOnPrimary,

    ripple = ColorTokens.Dark.Ripple,
    overlay = ColorTokens.Dark.Overlay,
    scrim = ColorTokens.Dark.Scrim,

    buttonPrimary = ColorTokens.Primary,
    buttonSecondary = ColorTokens.Dark.BackgroundTertiary,
    buttonDisabled = ColorTokens.Dark.BackgroundDisabled,

    statusBarColor = ColorTokens.Dark.Background,
    navigationBarColor = ColorTokens.Dark.Background,

    isDark = true
)

/**
 * CompositionLocal for DifftExtendedColors
 */
val LocalDifftExtendedColors = staticCompositionLocalOf { createLightExtendedColors() }
