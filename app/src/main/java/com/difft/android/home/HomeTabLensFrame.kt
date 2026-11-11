package com.difft.android.home


internal class HomeTabLensFrame {
    private val applied = LensFrame(0f, 1f, 1f)
    private val replay = LensFrame(0f, 1f, 1f)
    private var positioned = false

    fun apply(index: Float, scaleX: Float, scaleY: Float) {
        applied.set(index, scaleX, scaleY)
        positioned = true
    }

    fun resolve(index: Float, scaleX: Float, scaleY: Float): LensFrame {
        
        
        if (!positioned || (applied.index == index && applied.scaleX == scaleX && applied.scaleY == scaleY)) {
            replay.set(index, scaleX, scaleY)
        } else replay.set(applied.index, applied.scaleX, applied.scaleY)
        return replay
    }
}

internal data class LensFrame(var index: Float, var scaleX: Float, var scaleY: Float) {
    fun set(index: Float, scaleX: Float, scaleY: Float) {
        this.index = index
        this.scaleX = scaleX
        this.scaleY = scaleY
    }
}
