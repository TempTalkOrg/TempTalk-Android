package com.difft.android.call.session

import com.difft.android.call.ui.barrage.BarrageRenderable


sealed interface CallSessionUiEvent {
    
    data class Barrage(val renderable: BarrageRenderable) : CallSessionUiEvent

    data class Toast(val message: String) : CallSessionUiEvent

    
    data object RevealCallChrome : CallSessionUiEvent
}
