



package com.difft.android.home



internal object HomeTabLensMath {
    fun progress(pressed: Float): Float = finite(pressed).coerceIn(0f, 1f)

    fun scale(velocity: Float, axis: Float, cross: Float, result: PillScale = PillScale(1f, 1f)): PillScale {
        val speed = finite(velocity) / 10f
        result.axis = safeScale(axis) / (1f - (speed * 0.75f).coerceIn(-0.2f, 0.2f))
        result.cross = safeScale(cross) * (1f - (speed * 0.25f).coerceIn(-0.2f, 0.2f))
        return result
    }

    fun restTintAlpha(progress: Float): Float = 0.1f * (1f - this.progress(progress))

    
    fun restTintAlphaLight(progress: Float): Float = 0.06f * (1f - this.progress(progress))
    fun pressedTintAlpha(progress: Float): Float = 0.03f * this.progress(progress)

    fun physicalStart(offset: Float, pillExtent: Float, totalExtent: Float, reverse: Boolean): Float =
        if (reverse) totalExtent - pillExtent - offset else offset

    fun axisPosition(offset: Float, pillAxisExtent: Float, stripAxisExtent: Float, vertical: Boolean, rtl: Boolean): Float =
        physicalStart(offset, pillAxisExtent, stripAxisExtent, reverse = !vertical && rtl)

    fun physicalX(axisPosition: Float, vertical: Boolean): Float = if (vertical) 0f else axisPosition
    fun physicalY(axisPosition: Float, vertical: Boolean): Float = if (vertical) axisPosition else 0f
    fun physicalScaleX(scale: PillScale, vertical: Boolean): Float = if (vertical) scale.cross else scale.axis
    fun physicalScaleY(scale: PillScale, vertical: Boolean): Float = if (vertical) scale.axis else scale.cross

    fun rowTransform(x: Float, y: Float, width: Float, height: Float, scaleX: Float, scaleY: Float,
        result: LensRowTransform = LensRowTransform(1f, 1f, 0f, 0f),
    ): LensRowTransform {
        val sx = safeScale(scaleX)
        val sy = safeScale(scaleY)
        result.scaleX = 1f / sx
        result.scaleY = 1f / sy
        result.translateX = -(x + (1f - sx) * width / 2f) / sx + 0f 
        result.translateY = -(y + (1f - sy) * height / 2f) / sy + 0f
        return result
    }

    private fun finite(value: Float) = if (value.isFinite()) value else 0f
    private fun safeScale(value: Float) = if (value.isFinite() && value > 0f) value else 1f
}

internal data class LensRowTransform(var scaleX: Float, var scaleY: Float, var translateX: Float, var translateY: Float)
