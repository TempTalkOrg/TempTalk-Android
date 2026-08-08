package com.difft.android.imageeditor.core.renderers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Parcel
import android.os.Parcelable

/**
 * Given points for a line to go though, automatically finds control points.
 *
 * Based on  http://www.particleincell.com/2012/bezier-splines/
 *
 * Can then draw that line to a [Canvas] given a [Paint].
 *
 * Allocation efficient so that adding new points does not result in lots of array allocations.
 *
 * Hand-rolled [Parcelable] (not @Parcelize): the parcel form is the trimmed knot arrays
 * (`copyOfRange(x, 0, count)`) with `count` re-derived from the array length on read and the
 * control-point/working memory re-allocated + recomputed in the constructor — @Parcelize would
 * store the 256-capacity backing arrays and a separate `count`, mis-reconstructing the object. #1093
 */
internal class AutomaticControlPointBezierLine private constructor(
    x: FloatArray?,
    y: FloatArray?,
    count: Int
) : Parcelable {

    private var x: FloatArray = x ?: FloatArray(INITIAL_CAPACITY)
    private var y: FloatArray = y ?: FloatArray(INITIAL_CAPACITY)

    // control points
    private var p1x = FloatArray(0)
    private var p1y = FloatArray(0)
    private var p2x = FloatArray(0)
    private var p2y = FloatArray(0)

    private var count: Int = count

    private val path = Path()

    // rhs vector for computeControlPoints method
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

    /**
     * Adds a new point to the end of the line but ignores points that are too close to the last.
     *
     * @param x         new x point
     * @param y         new y point
     * @param thickness the maximum distance to allow, line thickness is recommended.
     */
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

    /**
     * Adds a new point to the end of the line.
     *
     * @param x new x point
     * @param y new y point
     */
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

    /**
     * Draw the line.
     *
     * @param canvas The canvas to draw on.
     * @param paint  The paint to use.
     */
    fun draw(canvas: Canvas, paint: Paint) {
        canvas.drawPath(path, paint)
    }

    /**
     * Based on  http://www.particleincell.com/2012/bezier-splines/
     *
     * @param k     knots x or y, must be at least 2 entries
     * @param p1    corresponding first control point x or y
     * @param p2    corresponding second control point x or y
     * @param count number of k to process
     */
    private fun computeControlPoints(k: FloatArray, p1: FloatArray, p2: FloatArray, count: Int) {
        val n = count - 1

        // left most segment
        a[0] = 0f
        b[0] = 2f
        c[0] = 1f
        r[0] = k[0] + 2 * k[1]

        // internal segments
        for (i in 1 until n - 1) {
            a[i] = 1f
            b[i] = 4f
            c[i] = 1f
            r[i] = 4 * k[i] + 2 * k[i + 1]
        }

        // right segment
        a[n - 1] = 2f
        b[n - 1] = 7f
        c[n - 1] = 0f
        r[n - 1] = 8 * k[n - 1] + k[n]

        // solves Ax=b with the Thomas algorithm
        for (i in 1 until n) {
            val m = a[i] / b[i - 1]
            b[i] = b[i] - m * c[i - 1]
            r[i] = r[i] - m * r[i - 1]
        }

        p1[n - 1] = r[n - 1] / b[n - 1]
        for (i in n - 2 downTo 0) {
            p1[i] = (r[i] - c[i] * p1[i + 1]) / b[i]
        }

        // we have p1, now compute p2
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
