package com.difft.android.base.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.difft.android.base.ui.theme.tokens.ColorTokens





@Immutable
data class DifftExtendedColors(
    
    val success: Color,
    val warning: Color,
    val info: Color,

    
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

    
    val background: Color,
    val bg: Color,    
    val backgroundSecondary: Color,  
    val backgroundTertiary: Color,
    val backgroundQuaternary: Color,  
    val backgroundDisabled: Color,
    val backgroundPopup: Color,  
    val backgroundModal: Color,  
    val backgroundTooltip: Color,
    val backgroundPagePrimary: Color,  
    val bg5: Color,  
    val bgWhite: Color,  

    
    val primaryPressed: Color,
    val fill: Color,
    val selected: Color,
    val avsysBg: Color,
    val avsysFg: Color,
    val line: Color,
    val divider: Color,
    val dividerFloating: Color,
    val icon: Color,
    val iconOnPrimary: Color,

    
    val bubbleSent: Color,
    val bubbleSentText: Color,
    val bubbleSentMeta: Color,
    val bubbleSentLink: Color,
    val bubbleSentQuote: Color,
    val bubbleSentBar: Color,
    val bubbleRecv: Color,
    val bubbleRecvQuote: Color,
    val mediaTimeBg: Color,

    
    val isDark: Boolean
)





fun createLightColorScheme(): ColorScheme = lightColorScheme(
    primary = ColorTokens.Light.Primary,
    onPrimary = ColorTokens.Light.OnPrimary,
    primaryContainer = ColorTokens.Light.Fill,
    onPrimaryContainer = ColorTokens.Light.Primary,

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
    inversePrimary = ColorTokens.Dark.Primary,

    surfaceTint = ColorTokens.Light.Primary
)





fun createDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = ColorTokens.Dark.Primary,
    onPrimary = ColorTokens.Dark.OnPrimary,
    primaryContainer = ColorTokens.Dark.Fill,
    onPrimaryContainer = ColorTokens.Dark.Primary,

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
    inversePrimary = ColorTokens.Light.Primary,

    surfaceTint = ColorTokens.Dark.Primary
)




fun createLightExtendedColors(): DifftExtendedColors = DifftExtendedColors(
    success = ColorTokens.Success,
    warning = ColorTokens.Warning,
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
    backgroundPagePrimary = ColorTokens.Light.BackgroundPagePrimary,
    bg5 = ColorTokens.Light.Bg5,
    bgWhite = ColorTokens.BgWhite,

    primaryPressed = ColorTokens.Light.PrimaryPressed,
    fill = ColorTokens.Light.Fill,
    selected = ColorTokens.Light.Selected,
    avsysBg = ColorTokens.Light.AvsysBg,
    avsysFg = ColorTokens.Light.AvsysFg,

    line = ColorTokens.Light.Line,
    divider = ColorTokens.Light.Divider,
    dividerFloating = ColorTokens.Light.DividerFloating,
    icon = ColorTokens.Light.Icon,
    iconOnPrimary = ColorTokens.Light.IconOnPrimary,

    bubbleSent = ColorTokens.BubbleSent,
    bubbleSentText = ColorTokens.BubbleSentText,
    bubbleSentMeta = ColorTokens.BubbleSentMeta,
    bubbleSentLink = ColorTokens.BubbleSentLink,
    bubbleSentQuote = ColorTokens.Light.BubbleSentQuote,
    bubbleSentBar = ColorTokens.Light.BubbleSentBar,
    bubbleRecv = ColorTokens.Light.BubbleRecv,
    bubbleRecvQuote = ColorTokens.Light.BubbleRecvQuote,
    mediaTimeBg = ColorTokens.MediaTimeBg,

    isDark = false
)




fun createDarkExtendedColors(): DifftExtendedColors = DifftExtendedColors(
    success = ColorTokens.Success,
    warning = ColorTokens.Warning,
    info = ColorTokens.Info,

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
    backgroundPagePrimary = ColorTokens.Dark.BackgroundPagePrimary,
    bg5 = ColorTokens.Dark.Bg5,
    bgWhite = ColorTokens.BgWhite,

    primaryPressed = ColorTokens.Dark.PrimaryPressed,
    fill = ColorTokens.Dark.Fill,
    selected = ColorTokens.Dark.Selected,
    avsysBg = ColorTokens.Dark.AvsysBg,
    avsysFg = ColorTokens.Dark.AvsysFg,

    line = ColorTokens.Dark.Line,
    divider = ColorTokens.Dark.Divider,
    dividerFloating = ColorTokens.Dark.DividerFloating,
    icon = ColorTokens.Dark.Icon,
    iconOnPrimary = ColorTokens.Dark.IconOnPrimary,

    bubbleSent = ColorTokens.BubbleSent,
    bubbleSentText = ColorTokens.BubbleSentText,
    bubbleSentMeta = ColorTokens.BubbleSentMeta,
    bubbleSentLink = ColorTokens.BubbleSentLink,
    bubbleSentQuote = ColorTokens.Dark.BubbleSentQuote,
    bubbleSentBar = ColorTokens.Dark.BubbleSentBar,
    bubbleRecv = ColorTokens.Dark.BubbleRecv,
    bubbleRecvQuote = ColorTokens.Dark.BubbleRecvQuote,
    mediaTimeBg = ColorTokens.MediaTimeBg,

    isDark = true
)




val LocalDifftExtendedColors = staticCompositionLocalOf { createLightExtendedColors() }
