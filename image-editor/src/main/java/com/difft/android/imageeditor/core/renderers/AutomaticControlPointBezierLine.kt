package com.difft.android.imageeditor.core.renderers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Parcel
import android.os.Parcelable















internal class AutomaticControlPointBezierLine private constructor(
    x: FloatArray?,
    y: FloatArray?,
    count: Int
) : Parcelable {

    private var x: FloatArray = x ?: FloatArray(INITIAL_CAPACITY)
    private var y: FloatArray = y ?: FloatArray(INITIAL_CAPACITY)

    
    private var p1x = FloatArray(0)
    private var p1y = FloatArray(0)
    private var p2x = FloatArray(0)
    private var p2y = FloatArray(0)

    private var count: Int = count

    private val path = Path()

    
    private var a = FloatArray(0)
    private var b = FloatArray(0)
    private var c = FloatArray(0)
    private var r = FloatArray(0)

    init {
        allocControlPointsAndWorkingMemory(this.x.size)
        recalculateControlPoints()
    }

    constructor() : this(null, null, 0)

    fun reset() {
        count = 0
        path.reset()
    }

    






    fun addPointFiltered(x: Float, y: Float, thickness: Float) {
        if (count > 0) {
            val dx = this.x[count - 1] - x
            val dy = this.y[count - 1] - y
            if (dx * dx + dy * dy < thickness * thickness) {
                return
            }
        }
        addPoint(x, y)
    }

    





    fun addPoint(x: Float, y: Float) {
        if (count == this.x.size) {
            resize(this.x.size shl 1)
        }

        this.x[count] = x
        this.y[count] = y
        count++

        recalculateControlPoints()
    }

    private fun resize(newCapacity: Int) {
        x = x.copyOf(newCapacity)
        y = y.copyOf(newCapacity)
        allocControlPointsAndWorkingMemory(newCapacity - 1)
    }

    private fun allocControlPointsAndWorkingMemory(max: Int) {
        p1x = FloatArray(max)
        p1y = FloatArray(max)
        p2x = FloatArray(max)
        p2y = FloatArray(max)

        a = FloatArray(max)
        b = FloatArray(max)
        c = FloatArray(max)
        r = FloatArray(max)
    }

    private fun recalculateControlPoints() {
        path.reset()

        if (count > 2) {
            computeControlPoints(x, p1x, p2x, count)
            computeControlPoints(y, p1y, p2y, count)
        }

        path.moveTo(x[0], y[0])
        when (count) {
            1 -> path.lineTo(x[0], y[0])
            2 -> path.lineTo(x[1], y[1])
            else -> {
                for (i in 1 until count - 1) {
                    path.cubicTo(p1x[i], p1y[i], p2x[i], p2y[i], x[i + 1], y[i + 1])
                }
            }
        }
    }

    





    fun draw(canvas: Canvas, paint: Paint) {
        canvas.drawPath(path, paint)
    }

    







    private fun computeControlPoints(k: FloatArray, p1: FloatArray, p2: FloatArray, count: Int) {
        val n = count - 1

        
        a[0] = 0f
        b[0] = 2f
        c[0] = 1f
        r[0] = k[0] + 2 * k[1]

        
        for (i in 1 until n - 1) {
            a[i] = 1f
            b[i] = 4f
            c[i] = 1f
            r[i] = 4 * k[i] + 2 * k[i + 1]
        }

        
        a[n - 1] = 2f
        b[n - 1] = 7f
        c[n - 1] = 0f
        r[n - 1] = 8 * k[n - 1] + k[n]

        
        for (i in 1 until n) {
            val m = a[i] / b[i - 1]
            b[i] = b[i] - m * c[i - 1]
            r[i] = r[i] - m * r[i - 1]
        }

        p1[n - 1] = r[n - 1] / b[n - 1]
        for (i in n - 2 downTo 0) {
            p1[i] = (r[i] - c[i] * p1[i + 1]) / b[i]
        }

        
        for (i in 0 until n - 1) {
            p2[i] = 2 * k[i + 1] - p1[i + 1]
        }

        p2[n - 1] = 0.5f * (k[n] + p1[n - 1])
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeFloatArray(x.copyOfRange(0, count))
        dest.writeFloatArray(y.copyOfRange(0, count))
    }

    companion object {
        private const val INITIAL_CAPACITY = 256

        @JvmField
        val CREATOR: Parcelable.Creator<AutomaticControlPointBezierLine> =
            object : Parcelable.Creator<AutomaticControlPointBezierLine> {
                override fun createFromParcel(`in`: Parcel): AutomaticControlPointBezierLine {
                    val x = `in`.createFloatArray()
                    val y = `in`.createFloatArray()
                    return AutomaticControlPointBezierLine(x, y, x?.size ?: 0)
                }

                override fun newArray(size: Int): Array<AutomaticControlPointBezierLine?> = arrayOfNulls(size)
            }
    }
}
