package com.difft.android.call.session

import com.difft.android.call.ui.barrage.BarrageRenderable

/** One-shot session output for the UI, delivered through [CallSessionUiSource.events]. */
sealed interface CallSessionUiEvent {
    /** A formatted barrage / bubble; the formatting already ran off main in the session. */
    data class Barrage(val renderable: BarrageRenderable) : CallSessionUiEvent

    data class Toast(val message: String) : CallSessionUiEvent

    /** Screen share started or stopped: show the top status bar and the bottom toolbar. */
    data object RevealCallChrome : CallSessionUiEvent
}
