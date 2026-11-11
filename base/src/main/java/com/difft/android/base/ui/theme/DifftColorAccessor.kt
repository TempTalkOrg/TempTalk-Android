package com.difft.android.base.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color





object DifftColorAccessor {
    
    
    
    val primary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    val onPrimary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onPrimary

    val error: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.error

    




    val background: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.background

    
    private val extended: DifftExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalDifftExtendedColors.current

    val info: Color
        @Composable @ReadOnlyComposable
        get() = extended.info

    val primaryPressed: Color
        @Composable @ReadOnlyComposable
        get() = extended.primaryPressed

    val fill: Color
        @Composable @ReadOnlyComposable
        get() = extended.fill

    val selected: Color
        @Composable @ReadOnlyComposable
        get() = extended.selected

    val avsysBg: Color
        @Composable @ReadOnlyComposable
        get() = extended.avsysBg

    val avsysFg: Color
        @Composable @ReadOnlyComposable
        get() = extended.avsysFg

    val bubbleSent: Color
        @Composable @ReadOnlyComposable
        get() = extended.bubbleSent

    val bubbleSentText: Color
        @Composable @ReadOnlyComposable
        get() = extended.bubbleSentText

    val bubbleSentMeta: Color
        @Composable @ReadOnlyComposable
        get() = extended.bubbleSentMeta

    val bubbleSentLink: Color
        @Composable @ReadOnlyComposable
        get() = extended.bubbleSentLink

    val bubbleSentQuote: Color
        @Composable @ReadOnlyComposable
        get() = extended.bubbleSentQuote

    val bubbleSentBar: Color
        @Composable @ReadOnlyComposable
        get() = extended.bubbleSentBar

    val bubbleRecv: Color
        @Composable @ReadOnlyComposable
        get() = extended.bubbleRecv

    val bubbleRecvQuote: Color
        @Composable @ReadOnlyComposable
        get() = extended.bubbleRecvQuote

    val mediaTimeBg: Color
        @Composable @ReadOnlyComposable
        get() = extended.mediaTimeBg

    val success: Color
        @Composable @ReadOnlyComposable
        get() = extended.success

    val warning: Color
        @Composable @ReadOnlyComposable
        get() = extended.warning

    val textPrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.textPrimary

    val textSecondary: Color
        @Composable @ReadOnlyComposable
        get() = extended.textSecondary

    val textTertiary: Color
        @Composable @ReadOnlyComposable
        get() = extended.textTertiary

    val textDisabled: Color
        @Composable @ReadOnlyComposable
        get() = extended.textDisabled

    val textOnPrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.textOnPrimary

    val textError: Color
        @Composable @ReadOnlyComposable
        get() = extended.textError

    val textWarning: Color
        @Composable @ReadOnlyComposable
        get() = extended.textWarning

    val textSuccess: Color
        @Composable @ReadOnlyComposable
        get() = extended.textSuccess

    val textInfo: Color
        @Composable @ReadOnlyComposable
        get() = extended.textInfo

    
    val textCaution: Color
        @Composable @ReadOnlyComposable
        get() = extended.textCaution

    












    val bg: Color
        @Composable @ReadOnlyComposable
        get() = extended.bg

    val backgroundSecondary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundSecondary

    val backgroundTertiary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundTertiary

    val backgroundQuaternary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundQuaternary

    val backgroundDisabled: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundDisabled

    








    val backgroundPopup: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundPopup

    
    val backgroundModal: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundModal

    val backgroundTooltip: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundTooltip

    
    val backgroundPagePrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundPagePrimary

    



    val bg5: Color
        @Composable @ReadOnlyComposable
        get() = extended.bg5

    
    val bgWhite: Color
        @Composable @ReadOnlyComposable
        get() = extended.bgWhite

    val line: Color
        @Composable @ReadOnlyComposable
        get() = extended.line

    
    val divider: Color
        @Composable @ReadOnlyComposable
        get() = extended.divider

    
    val dividerFloating: Color
        @Composable @ReadOnlyComposable
        get() = extended.dividerFloating

    val icon: Color
        @Composable @ReadOnlyComposable
        get() = extended.icon

    val iconOnPrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.iconOnPrimary

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = extended.isDark
}
