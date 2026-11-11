package com.difft.android.base.utils.density

import com.difft.android.base.utils.WindowSizeClassUtil
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt































object DensityNormalization {

    
    data class Inputs(
        val densityDpi: Int,
        val stableDensityDpi: Int,
        val xdpi: Float,
        val ydpi: Float,
        





        val modeWidthPx: Int,
        val modeHeightPx: Int,
        
        val maxModeLongPx: Int = max(modeWidthPx, modeHeightPx),
        
        val realWidthPx: Int = 0,
        val realHeightPx: Int = 0,
        
        val windowWidthDp: Int = 0,
        val windowHeightDp: Int = 0,
    )

    enum class Reason { APPLIED, NEUTRAL, PLACEHOLDER, NON_SQUARE, IMPLAUSIBLE_SIZE, IMPLAUSIBLE_BIAS }

    data class Decision(
        
        val densityDpi: Int,
        
        val standardDpi: Int,
        
        val reason: Reason,
        val factor: Float,
        
        val ppi: Float,
        val baselineIsStable: Boolean,
        
        val systemRatio: Float,
    )

    private const val MIN_FACTOR = 0.85f
    private const val NEUTRAL_FACTOR = 0.98f
    private const val MAX_AXIS_SKEW = 0.05f
    private const val MIN_DIAGONAL_IN = 2.5
    private const val MAX_DIAGONAL_IN = 8.5
    private const val MAX_TALL_DIAGONAL_IN = 7.2
    private const val TALL_ASPECT = 1.9
    private const val MIN_PPI_RATIO = 0.70f
    private const val MAX_PPI_RATIO = 1.10f
    private const val CDD_MIN_DEFAULT_SHARE = 0.92f
    private const val CDD_SLACK = 1.02f
    private const val MIN_USER_RATIO = 0.85f
    private const val MAX_USER_RATIO = 1.5f
    private const val USER_RATIO_SLACK = 0.02f
    private val PLACEHOLDER_DPI = setOf(160f, 213f, 320f)

    
    private const val MIN_RENDERED_WIDTH_DP = 320

    
    
    private const val GATE_WIDTH_INSET_DP = 48
    private const val GATE_HEIGHT_INSET_DP = 80

    









    fun enlargementCap(inputs: Inputs): Int {
        val current = inputs.densityDpi
        val modeLong = max(inputs.modeWidthPx, inputs.modeHeightPx)
        val modeShort = min(inputs.modeWidthPx, inputs.modeHeightPx)
        val realLong = max(inputs.realWidthPx, inputs.realHeightPx)
        val realShort = min(inputs.realWidthPx, inputs.realHeightPx)
        val long = if (realLong > 0) min(realLong, modeLong) else modeLong
        val short = if (realShort > 0) min(realShort, modeShort) else modeShort
        if (current <= 0 || short <= 0) return current

        var cap = short * BASELINE_DPI / MIN_RENDERED_WIDTH_DP
        val gateW = WindowSizeClassUtil.DUAL_PANE_MIN_WIDTH_DP
        val gateH = WindowSizeClassUtil.MIN_HEIGHT_FOR_DUAL_PANE_DP
        for ((w, h) in listOf(long to short, short to long)) {
            if (w * BASELINE_DPI / current >= gateW && h * BASELINE_DPI / current >= gateH) {
                cap = minOf(cap, w * BASELINE_DPI / (gateW + GATE_WIDTH_INSET_DP), h * BASELINE_DPI / (gateH + GATE_HEIGHT_INSET_DP))
            }
        }
        if (inputs.windowWidthDp >= gateW && inputs.windowHeightDp >= gateH) {
            cap = minOf(cap, current * inputs.windowWidthDp / gateW, current * inputs.windowHeightDp / gateH)
        }
        return max(cap, current)
    }

    private const val BASELINE_DPI = 160

    fun decide(inputs: Inputs, scale: Float = 1f): Decision {
        val current = inputs.densityDpi
        val modeLong = max(inputs.modeWidthPx, inputs.modeHeightPx)
        val modeShort = min(inputs.modeWidthPx, inputs.modeHeightPx)
        val maxModeLong = max(inputs.maxModeLongPx, modeLong)
        val stable = if (modeLong > 0) (inputs.stableDensityDpi.toFloat() * modeLong / maxModeLong).roundToInt() else 0
        val systemRatio = if (stable > 0) current.toFloat() / stable else 0f
        val baselineIsStable = systemRatio >= MIN_USER_RATIO - USER_RATIO_SLACK &&
            systemRatio <= MAX_USER_RATIO + USER_RATIO_SLACK
        val baseline = if (baselineIsStable) stable else current

        fun result(reason: Reason, ppi: Float = 0f, factor: Float = 1f): Decision {
            val standard = (baseline * factor).roundToInt()
            return Decision(
                densityDpi = min((standard * scale).roundToInt(), enlargementCap(inputs)),
                standardDpi = standard,
                reason = reason,
                factor = factor,
                ppi = ppi,
                baselineIsStable = baselineIsStable,
                systemRatio = if (baselineIsStable) systemRatio else 1f,
            )
        }

        val x = inputs.xdpi
        val y = inputs.ydpi
        if (isPlaceholder(x, inputs) || isPlaceholder(y, inputs)) return result(Reason.PLACEHOLDER)
        if (abs(x - y) / min(x, y) > MAX_AXIS_SKEW) return result(Reason.NON_SQUARE)

        val ppi = (x + y) / 2f
        if (modeShort <= 0) return result(Reason.PLACEHOLDER)

        val diagonalIn = hypot(modeLong.toDouble(), modeShort.toDouble()) / ppi
        val aspect = modeLong.toDouble() / modeShort
        val maxDiagonal = if (aspect >= TALL_ASPECT) MAX_TALL_DIAGONAL_IN else MAX_DIAGONAL_IN
        if (diagonalIn < MIN_DIAGONAL_IN || diagonalIn > maxDiagonal) {
            return result(Reason.IMPLAUSIBLE_SIZE, ppi)
        }

        val ppiRatio = ppi / baseline
        val cddBound = baseline / CDD_MIN_DEFAULT_SHARE * CDD_SLACK
        if (ppiRatio < MIN_PPI_RATIO || ppiRatio > MAX_PPI_RATIO || ppi > cddBound) {
            return result(Reason.IMPLAUSIBLE_BIAS, ppi)
        }

        val factor = ((ppi / baseline).coerceIn(MIN_FACTOR, 1f) * 100).roundToInt() / 100f
        if (factor > NEUTRAL_FACTOR) return result(Reason.NEUTRAL, ppi)
        return result(Reason.APPLIED, ppi, factor)
    }

    private fun isPlaceholder(dpi: Float, inputs: Inputs): Boolean =
        !dpi.isFinite() || dpi <= 0f || dpi in PLACEHOLDER_DPI ||
            dpi == inputs.densityDpi.toFloat() || dpi == inputs.stableDensityDpi.toFloat()
}
