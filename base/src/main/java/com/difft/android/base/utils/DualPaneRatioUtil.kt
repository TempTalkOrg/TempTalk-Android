package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow














object DualPaneRatioUtil {

    
    const val NO_OVERRIDE: Float = -1f

    



    private const val MIN_RATIO = 0.1f
    private const val MAX_RATIO = 0.9f

    private val _ratioState = MutableStateFlow(NO_OVERRIDE)
    val ratioState: StateFlow<Float> = _ratioState.asStateFlow()

    
    val currentRatio: Float
        get() = _ratioState.value

    
    val hasUserOverride: Boolean
        get() = _ratioState.value > 0f

    










    fun loadAndEmit() {
        try {
            val ratio = globalServices.userManager.getUserData()?.dualPaneRatio ?: NO_OVERRIDE
            _ratioState.value = ratio
        } catch (e: Exception) {
            L.w { "[DualPaneRatio] load failed: ${e.stackTraceToString()}" }
            _ratioState.value = NO_OVERRIDE
        }
    }

    




    fun updateRatio(ratio: Float) {
        val clamped = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
        try {
            globalServices.userManager.update { this.dualPaneRatio = clamped }
        } catch (e: Exception) {
            L.w { "[DualPaneRatio] persist failed: ${e.stackTraceToString()}" }
        }
        _ratioState.value = clamped
    }
}
