// Synced from call-layout bf6b84ecbe, ports/kotlin/src/main/kotlin/org/difft/calllayout/Geometry.kt (spec v25), by scripts/sync-apps.ts.
// Do not edit here: change call-layout and sync again.

package com.difft.android.call.layout

import kotlin.math.max
import kotlin.math.min

// Plans are rounded so golden vectors compare cleanly across platforms. Math.round rounds half
// up, as JS Math.round does.
fun r2(n: Double): Double = Math.round(n * 100) / 100.0

fun roundRect(r: Rect): Rect = Rect(r2(r.x), r2(r.y), r2(r.w), r2(r.h))

// What an app can draw whatever the input: no NaN or infinity, no negative size.
fun saneRect(r: Rect): Rect {
    fun n(v: Double) = if (v.isFinite()) v else 0.0
    return Rect(n(r.x), n(r.y), max(0.0, n(r.w)), max(0.0, n(r.h)))
}

fun aspectOf(s: Size): Double = if (s.h > 0) s.w / s.h else 0.0

// Share of the picture lost when one aspect is shown in the other:
// cropped when filled, letterboxed when fitted. 0 means a perfect match.
fun mismatch(src: Double, dst: Double): Double {
    if (!(src > 0 && dst > 0)) return 0.0
    return 1 - min(src, dst) / max(src, dst)
}

fun contentModeFor(src: Double, dst: Double, threshold: Double): ContentMode =
    if (mismatch(src, dst) <= threshold) ContentMode.FILL else ContentMode.FIT

fun fitRect(aspect: Double, box: Rect): Rect {
    var w = box.w
    var h = w / aspect
    if (h > box.h) {
        h = box.h
        w = h * aspect
    }
    return Rect(box.x + (box.w - w) / 2, box.y + (box.h - h) / 2, w, h)
}

fun coverRect(aspect: Double, box: Rect): Rect {
    var w = box.w
    var h = w / aspect
    if (h < box.h) {
        h = box.h
        w = h * aspect
    }
    return Rect(box.x + (box.w - w) / 2, box.y + (box.h - h) / 2, w, h)
}

// Shrinks one side so w / h lands inside [min, max].
fun clampAspect(w: Double, h: Double, min: Double, max: Double): Size {
    val a = w / h
    if (a > max) return Size(h * max, h)
    if (a < min) return Size(w, w / min)
    return Size(w, h)
}
