package com.difft.android.base.ui.compose

import android.os.SystemClock
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens

/**
 * The design library's button scale (Wea Design system, page `Button`, component set
 * `1359:20425`). Each size carries its height, padding, corner radius and type-scale step.
 *
 * - [S] 24dp inline action — below the 48dp touch minimum, so a caller must give it surrounding
 *   slop or a larger touch parent
 * - [M] 32dp compact action inside a row or card
 * - [L] 40dp the library's standard button
 * - [XL] 48dp full-width page CTA (the default: every page-level button in the app is 48dp tall)
 */
enum class DifftButtonSize { S, M, L, XL }

/**
 * Button emphasis. Only [Secondary] is outlined; every size can take every variant.
 *
 * - [Primary] `primary` fill, `textOnPrimary` label — the CTA
 * - [Secondary] no fill, 1dp `line` border, `textPrimary` label — the Cancel / Not Now next to a CTA
 * - [Danger] `error` fill, white label — a destructive confirmation
 * - [Success] `success` fill, white label — a positive confirmation
 * - [Text] no fill or border, `textInfo` label — the least prominent action
 */
enum class DifftButtonVariant { Primary, Secondary, Danger, Success, Text }

/** Single source for [DifftButton]'s geometry and colours; the View shell reuses it. */
object DifftButtonDefaults {

    /**
     * Clicks closer together than this on one button instance are dropped. The global wait dialog
     * a CTA raises is a real modal window, but its platform window only becomes modal once the
     * composition it was scheduled from produces a frame, leaving one frame in which a second tap
     * still reaches the button. This closes that window without giving `enabled` a second owner.
     */
    const val ClickDebounceMillis: Long = 300L

    /**
     * The debounce itself, one instance per button. Holds the next accepting time rather than the
     * last click, so the initial 0 accepts the first click at any uptime — a last-click form would
     * drop the very first click while uptime is still below the window.
     *
     * Main thread only.
     */
    class ClickGate {
        private var acceptFrom = 0L

        /**
         * @param now the reading to gate on. Defaults to the uptime clock, which a device time
         * change cannot skew.
         */
        fun tryAccept(now: Long = SystemClock.uptimeMillis()): Boolean {
            if (now < acceptFrom) return false
            acceptFrom = now + ClickDebounceMillis
            return true
        }
    }

    fun height(size: DifftButtonSize): Dp = when (size) {
        DifftButtonSize.S -> 24.dp
        DifftButtonSize.M -> 32.dp
        DifftButtonSize.L -> 40.dp
        DifftButtonSize.XL -> 48.dp
    }

    /**
     * The library's minimum widths, so a one-word label still reads as a button. There is no
     * maximum: the design has both content-width and full-width instances.
     */
    fun minWidth(size: DifftButtonSize): Dp = when (size) {
        DifftButtonSize.S -> 40.dp
        DifftButtonSize.M -> 52.dp
        DifftButtonSize.L, DifftButtonSize.XL -> 60.dp
    }

    /**
     * Library insets. S and M reach their height from padding + line height alone; L and XL
     * (20 line height + 16 / 24 padding = 36 / 44) are lifted to 40 / 48 by the min height.
     */
    fun contentPadding(size: DifftButtonSize): PaddingValues = when (size) {
        DifftButtonSize.S -> PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        DifftButtonSize.M -> PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        DifftButtonSize.L -> PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        DifftButtonSize.XL -> PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    }

    /** S and M are the 4dp tier, L and XL the 8dp one; [DifftButtonVariant.Text] is 4dp at any size. */
    fun shape(size: DifftButtonSize, variant: DifftButtonVariant = DifftButtonVariant.Primary): Shape = when {
        variant == DifftButtonVariant.Text -> DifftTheme.radius.mediumShape
        size == DifftButtonSize.S || size == DifftButtonSize.M -> DifftTheme.radius.mediumShape
        else -> DifftTheme.radius.largeShape
    }

    /** [DifftButtonVariant.Secondary]'s 1dp hairline. */
    val BorderWidth: Dp = 1.dp

    /** Black wash drawn over a filled variant while pressed. */
    val PressOverlay: Color = Color.Black.copy(alpha = 0.2f)

    /** [DifftButtonVariant.Danger] and [DifftButtonVariant.Success] fade whole rather than recolour. */
    const val DisabledAlpha: Float = 0.3f

    /** Labels are Regular: S the 12/16 step, every larger size the 14/20 one. */
    @Composable
    @ReadOnlyComposable
    fun textStyle(size: DifftButtonSize): TextStyle = when (size) {
        DifftButtonSize.S -> DifftTheme.typography.p4                                 // SF/P4 Regular 12/16
        DifftButtonSize.M, DifftButtonSize.L, DifftButtonSize.XL -> DifftTheme.typography.p3  // SF/P3 Regular 14/20
    }

    fun isOutlined(variant: DifftButtonVariant): Boolean = variant == DifftButtonVariant.Secondary

    /** Filled variants darken while pressed; the others swap their fill instead. */
    fun hasPressOverlay(variant: DifftButtonVariant): Boolean = when (variant) {
        DifftButtonVariant.Primary, DifftButtonVariant.Danger, DifftButtonVariant.Success -> true
        DifftButtonVariant.Secondary, DifftButtonVariant.Text -> false
    }

    /** Whether the disabled form is the whole button at [DisabledAlpha] instead of recoloured. */
    fun dimsWhenDisabled(variant: DifftButtonVariant): Boolean =
        variant == DifftButtonVariant.Danger || variant == DifftButtonVariant.Success

    @Composable
    @ReadOnlyComposable
    fun containerColor(
        variant: DifftButtonVariant,
        enabled: Boolean = true,
        pressed: Boolean = false,
    ): Color = when (variant) {
        DifftButtonVariant.Primary -> if (enabled) DifftTheme.colors.primary else DifftTheme.colors.backgroundDisabled
        DifftButtonVariant.Danger -> DifftTheme.colors.error
        DifftButtonVariant.Success -> DifftTheme.colors.success
        DifftButtonVariant.Secondary ->
            if (enabled && pressed) DifftTheme.colors.backgroundTertiary else Color.Transparent
        DifftButtonVariant.Text ->
            if (enabled && pressed) DifftTheme.colors.backgroundBlue else Color.Transparent
    }

    @Composable
    @ReadOnlyComposable
    fun contentColor(
        variant: DifftButtonVariant,
        enabled: Boolean = true,
    ): Color = when (variant) {
        // Danger and Success keep their white label and fade whole; see [dimsWhenDisabled].
        DifftButtonVariant.Danger, DifftButtonVariant.Success -> DifftTheme.colors.textOnPrimary
        DifftButtonVariant.Primary -> if (enabled) DifftTheme.colors.textOnPrimary else DisabledOnFill
        DifftButtonVariant.Secondary ->
            if (enabled) DifftTheme.colors.textPrimary else DifftTheme.colors.textDisabled
        DifftButtonVariant.Text ->
            if (enabled) DifftTheme.colors.textInfo else DifftTheme.colors.textDisabled
    }

    /**
     * Disabled label on a filled button. The library keeps #B7BDC6 in dark too — the dark
     * `textDisabled` #5E6673 would sit at ~1.3:1 on the #474D57 disabled fill. Button-local: the
     * token itself is correct for text on a page background.
     */
    private val DisabledOnFill: Color = ColorTokens.Light.TextDisabled

    /**
     * The border keeps `line` while disabled: `line` and `backgroundDisabled` are the same value
     * in both palettes (#EAECEF light, #474D57 dark), so a disabled branch would be dead code.
     */
    @Composable
    @ReadOnlyComposable
    fun borderColor(): Color = DifftTheme.colors.line
}
