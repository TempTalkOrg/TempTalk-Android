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










enum class DifftButtonSize { S, M, L, XL }










enum class DifftButtonVariant { Primary, Secondary, Danger, Success, Text }


object DifftButtonDefaults {

    





    const val ClickDebounceMillis: Long = 300L

    






    class ClickGate {
        private var acceptFrom = 0L

        



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

    



    fun minWidth(size: DifftButtonSize): Dp = when (size) {
        DifftButtonSize.S -> 40.dp
        DifftButtonSize.M -> 52.dp
        DifftButtonSize.L, DifftButtonSize.XL -> 60.dp
    }

    



    fun contentPadding(size: DifftButtonSize): PaddingValues = when (size) {
        DifftButtonSize.S -> PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        DifftButtonSize.M -> PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        DifftButtonSize.L -> PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        DifftButtonSize.XL -> PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    }

    
    fun shape(size: DifftButtonSize, variant: DifftButtonVariant = DifftButtonVariant.Primary): Shape = when {
        variant == DifftButtonVariant.Text -> DifftTheme.radius.mediumShape
        size == DifftButtonSize.S || size == DifftButtonSize.M -> DifftTheme.radius.mediumShape
        else -> DifftTheme.radius.largeShape
    }

    
    val BorderWidth: Dp = 1.dp

    
    val PressOverlay: Color = Color.Black.copy(alpha = 0.2f)

    
    const val DisabledAlpha: Float = 0.3f

    
    @Composable
    @ReadOnlyComposable
    fun textStyle(size: DifftButtonSize): TextStyle = when (size) {
        DifftButtonSize.S -> DifftTheme.typography.p4                                 
        DifftButtonSize.M, DifftButtonSize.L, DifftButtonSize.XL -> DifftTheme.typography.p3  
    }

    fun isOutlined(variant: DifftButtonVariant): Boolean = variant == DifftButtonVariant.Secondary

    
    fun hasPressOverlay(variant: DifftButtonVariant): Boolean = when (variant) {
        DifftButtonVariant.Danger, DifftButtonVariant.Success -> true
        DifftButtonVariant.Primary, DifftButtonVariant.Secondary, DifftButtonVariant.Text -> false
    }

    
    fun dimsWhenDisabled(variant: DifftButtonVariant): Boolean =
        variant == DifftButtonVariant.Danger || variant == DifftButtonVariant.Success

    @Composable
    @ReadOnlyComposable
    fun containerColor(
        variant: DifftButtonVariant,
        enabled: Boolean = true,
        pressed: Boolean = false,
    ): Color = when (variant) {
        DifftButtonVariant.Primary -> when {
            !enabled -> DifftTheme.colors.backgroundDisabled
            pressed -> DifftTheme.colors.primaryPressed
            else -> DifftTheme.colors.primary
        }
        DifftButtonVariant.Danger -> DifftTheme.colors.error
        DifftButtonVariant.Success -> DifftTheme.colors.success
        DifftButtonVariant.Secondary ->
            if (enabled && pressed) DifftTheme.colors.fill else Color.Transparent
        DifftButtonVariant.Text ->
            if (enabled && pressed) DifftTheme.colors.fill else Color.Transparent
    }

    @Composable
    @ReadOnlyComposable
    fun contentColor(
        variant: DifftButtonVariant,
        enabled: Boolean = true,
    ): Color = when (variant) {
        
        DifftButtonVariant.Danger, DifftButtonVariant.Success -> DifftTheme.colors.textOnPrimary
        DifftButtonVariant.Primary -> if (enabled) DifftTheme.colors.onPrimary else DisabledOnFill
        DifftButtonVariant.Secondary ->
            if (enabled) DifftTheme.colors.textPrimary else DifftTheme.colors.textDisabled
        DifftButtonVariant.Text ->
            if (enabled) DifftTheme.colors.primary else DifftTheme.colors.textDisabled
    }

    




    private val DisabledOnFill: Color = ColorTokens.Light.TextDisabled

    



    @Composable
    @ReadOnlyComposable
    fun borderColor(): Color = DifftTheme.colors.line
}
