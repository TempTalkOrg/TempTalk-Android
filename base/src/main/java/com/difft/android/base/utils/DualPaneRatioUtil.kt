package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-user persistence + StateFlow surface for the user-adjustable dual-pane split ratio.
 * Mirrors the [TextSizeUtil] pattern.
 *
 * The stored value is a normalized ratio (list_pane width / available pane space, where
 * "available" already excludes NavigationRail + dividers). A sentinel value of [NO_OVERRIDE]
 * means the user has never dragged — callers should fall back to the auto-layout defaults
 * (fixed 360dp for default text size, 50/50 for large text size).
 *
 * Storing the ratio (not absolute px) keeps the user's preference meaningful across rotation,
 * fold/unfold, and multi-window resizes — the same ratio applies to whatever the new available
 * space turns out to be.
 */
object DualPaneRatioUtil {

    /** Sentinel: user has not dragged the divider yet. Caller should use auto defaults. */
    const val NO_OVERRIDE: Float = -1f

    /**
     * Hard ratio bounds before the consumer applies its own min/max dp constraints.
     * The consumer should clamp again based on actual window width and min-pane requirements.
     */
    private const val MIN_RATIO = 0.1f
    private const val MAX_RATIO = 0.9f

    private val _ratioState = MutableStateFlow(NO_OVERRIDE)
    val ratioState: StateFlow<Float> = _ratioState.asStateFlow()

    /** Current ratio. Returns [NO_OVERRIDE] if user has not dragged yet. */
    val currentRatio: Float
        get() = _ratioState.value

    /** True if the user has explicitly set a ratio via dragging. */
    val hasUserOverride: Boolean
        get() = _ratioState.value > 0f

    /**
     * Load the persisted ratio from UserData and emit on the StateFlow.
     * Called once during Activity onCreate.
     *
     * Runs synchronously on the caller (typically the main thread): UserData is
     * preloaded into memory before this Activity reaches onCreate, so the read is
     * an in-memory snapshot lookup (microseconds). Going synchronous here ensures
     * the first `applyListPaneWidth()` call in onCreate sees the persisted ratio
     * — otherwise cold start would briefly show the default 360dp before the IO
     * coroutine landed and triggered a relayout to the saved ratio.
     */
    fun loadAndEmit() {
        try {
            val ratio = globalServices.userManager.getUserData()?.dualPaneRatio ?: NO_OVERRIDE
            _ratioState.value = ratio
        } catch (e: Exception) {
            L.w { "[DualPaneRatio] load failed: ${e.stackTraceToString()}" }
            _ratioState.value = NO_OVERRIDE
        }
    }

    /**
     * Persist and emit a new ratio. Called when the user finishes a drag (ACTION_UP).
     * Input is clamped to [MIN_RATIO, MAX_RATIO]; callers should apply their own
     * window-width-aware min/max before invoking this.
     */
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
