package com.difft.android.base.ui.theme.tokens

import androidx.compose.ui.graphics.Color

/**
 * Design tokens - Raw color values as single source of truth
 * These colors map to the Difft design system palette
 *
 * All colors are defined here and mapped to semantic roles in DifftColorScheme
 */
object ColorTokens {
    // ============== Brand Colors ==============
    val Primary = Color(0xFF056FFA)
    val PrimaryVariant = Color(0xFF2558C1)

    // ============== Functional Colors ==============
    val Success = Color(0xFF01BC6A)
    val SuccessDark = Color(0xFF00764B)
    val Warning = Color(0xFFFFC814)
    val WarningDark = Color(0xFFB06D00)
    val Error = Color(0xFFF84135)
    val ErrorDark = Color(0xFFD9271E)
    val Info = Color(0xFF056FFA)
    val InfoLight = Color(0xFF82C1FC)

    // ============== Light Theme Colors ==============
    object Light {
        // Background hierarchy
        val Background = Color(0xFFFFFFFF)           // bg1
        val BackgroundSecondary = Color(0xFFFAFAFA)  // bg.setting (bg2)
        val BackgroundTertiary = Color(0xFFF5F5F5)   // bg3
        val BackgroundDisabled = Color(0xFFEAECEF)
        val BackgroundBlue = Color(0xFFEBF7FF)
        val BackgroundSettingItem = Color(0xFFFFFFFF) // bg.setting.item

        // Surface colors
        val Surface = Color(0xFFF5F5F5)
        val SurfaceVariant = Color(0xFFF5F5F5)

        // Text hierarchy
        val TextPrimary = Color(0xFF1E2329)
        val TextSecondary = Color(0xFF474D57)
        val TextTertiary = Color(0xFF848E9C)
        val TextDisabled = Color(0xFFB7BDC6)
        val TextOnPrimary = Color(0xFFFFFFFF)

        // UI elements
        val Divider = Color(0xFFEAECEF)
        val Border = Color(0xFFEAECEF)
        val Icon = Color(0xFF474D57)
        val IconOnPrimary = Color(0xFFFFFFFF)

        // Special effects
        val Ripple = Color(0x1F056FFA)
        val Overlay = Color(0x33000000)
        val Scrim = Color(0x66000000)

        // Component specific
        val Tooltip = Color(0xFF5E6673)
    }

    // ============== Dark Theme Colors ==============
    object Dark {
        // Background hierarchy
        val Background = Color(0xFF181A20)           // bg1
        val BackgroundSecondary = Color(0xFF181A20)  // bg.setting (same as bg1 in dark mode)
        val BackgroundTertiary = Color(0xFF2B3139)   // bg3
        val BackgroundDisabled = Color(0xFF474D57)
        val BackgroundBlue = Color(0xFF003366)
        val BackgroundSettingItem = Color(0xFF1E2329) // bg.setting.item (bg2)

        // Surface colors
        val Surface = Color(0xFF2B3139)
        val SurfaceVariant = Color(0xFF2B3139)

        // Text hierarchy
        val TextPrimary = Color(0xFFEAECEF)
        val TextSecondary = Color(0xFFB7BDC6)
        val TextTertiary = Color(0xFF848E9C)
        val TextDisabled = Color(0xFF5E6673)
        val TextOnPrimary = Color(0xFFFFFFFF)

        // UI elements
        val Divider = Color(0xFF2B3139)
        val Border = Color(0xFF474D57)
        val Icon = Color(0xFFB7BDC6)
        val IconOnPrimary = Color(0xFFFFFFFF)

        // Special effects
        val Ripple = Color(0x1F82C1FC)
        val Overlay = Color(0x66000000)
        val Scrim = Color(0x99000000)

        // Component specific
        val Tooltip = Color(0xFF5E6673)
    }
}
