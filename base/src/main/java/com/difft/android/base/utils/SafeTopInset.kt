package com.difft.android.base.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt


const val CORNER_CUTOUT_GAP_DP = 4f





fun View.safeTopInset(insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(this)): Int {
    insets ?: return 0
    if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
        return insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
    }
    return maxOf(insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, hiddenStatusBarTop(insets))
}








fun View.hiddenStatusBarTop(insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(this)): Int {
    if (insets == null || insets.isVisible(WindowInsetsCompat.Type.statusBars())) return 0
    val width = windowWidth()
    val height = windowHeight()
    return hiddenBarEdgeCutouts(insets)
        .filter { !it.isCorner(width) && it.top < height / EDGE_BAND_FRACTION }
        .maxOfOrNull { it.bottom } ?: 0
}







fun View.cornerCutoutExtra(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(this),
): Pair<Int, Int> {
    if (insets == null || insets.isVisible(WindowInsetsCompat.Type.statusBars()) || right <= left) return 0 to 0
    val gap = (CORNER_CUTOUT_GAP_DP * resources.displayMetrics.density).roundToInt()
    val width = windowWidth()
    val middle = (left + right) / 2
    var start = 0
    var end = 0
    for (rect in hiddenBarEdgeCutouts(insets)) {
        if (!rect.isCorner(width) || rect.top >= bottom || rect.bottom <= top || rect.right <= left || rect.left >= right) continue
        if (rect.centerX() < middle) start = maxOf(start, rect.right + gap - left) else end = maxOf(end, right - rect.left + gap)
    }
    return start to end
}


fun View.cutoutRectsInWindow(insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(this)): List<Rect> {
    insets?.displayCutout?.let { return it.boundingRects }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !missesCutout(insets)) return emptyList()
    val rects = display?.cutout?.boundingRects ?: return emptyList()
    val location = IntArray(2)
    rootView.getLocationOnScreen(location)
    return rects.map { Rect(it).apply { offset(-location[0], -location[1]) } }
}


private fun View.hiddenBarEdgeCutouts(insets: WindowInsetsCompat): List<Rect> {
    val height = windowHeight()
    return cutoutRectsInWindow(insets).filter {
        !it.isEmpty && (it.top < height / EDGE_BAND_FRACTION || it.bottom > height - height / EDGE_BAND_FRACTION)
    }
}

private fun View.windowWidth(): Int = rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

private fun View.windowHeight(): Int = rootView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels


private fun Rect.isCorner(windowWidth: Int): Boolean =
    left < windowWidth / CORNER_FRACTION || right > windowWidth - windowWidth / CORNER_FRACTION






private fun View.missesCutout(insets: WindowInsetsCompat?): Boolean {
    insets ?: return false
    if (insets.isVisible(WindowInsetsCompat.Type.statusBars()) || insets.displayCutout != null) return false
    return context.findActivity()?.isInMultiWindowMode != true
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val EDGE_BAND_FRACTION = 4
private const val CORNER_FRACTION = 4
