package com.difft.android.chat.ui.messageaction

import androidx.compose.ui.graphics.Color
import com.difft.android.base.ui.theme.tokens.ColorTokens

/**
 * Forced-dark palette for the message action menu — independent of the app theme
 * (renders dark even under the Light theme). Shared by all five menu surfaces.
 *
 * Values source from [ColorTokens.Dark] + [ColorTokens.Error] (single source of truth,
 * not fresh literals). This is a feature-scoped dark-lock and deliberately does NOT go
 * through DifftColorAccessor, whose getters read the current theme.
 */
object MessageActionMenuColors {
    private const val PANEL_ALPHA = 0.97f

    /** Panel background: bg3 #2B3139 @ 0.97 alpha. */
    val panel: Color = ColorTokens.Dark.BackgroundTertiary.copy(alpha = PANEL_ALPHA)

    /** Default content (icon + label) tint: tprimary #EAECEF. */
    val contentDefault: Color = ColorTokens.Dark.TextPrimary

    /** Destructive content tint: terror #F84135. */
    val danger: Color = ColorTokens.Error

    /** Add-reaction icon tint: tthird #848E9C. */
    val reactionAddIcon: Color = ColorTokens.Dark.TextTertiary

    /** Grid divider line: rgba(255,255,255,0.07). */
    val gridLine: Color = Color(0x12FFFFFF)
}
