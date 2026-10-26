package com.difft.android.base.utils

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Moves this root up, by translation, just enough that [anchor] (the screen's action button)
 * ends [gapDp] above the keyboard, following the keyboard animation; returns to place when the
 * keyboard hides. For edge-to-edge screens padded for the system bars only, where adjustResize
 * does not resize the content, so a top-anchored form on a short window ends up under the
 * keyboard. The layout itself is untouched. A null or invisible anchor means no lift.
 *
 * The lift never moves [field] (the input) above the top padding: when the window is too short
 * for both, the field stays visible and the keyboard may cover part of the button.
 */
fun ViewGroup.liftAboveKeyboard(gapDp: Int = 16, field: () -> View?, anchor: () -> View?) {
    val root = this
    val gap = (gapDp * resources.displayMetrics.density).toInt()
    // IME animations in flight; they can overlap (hide interrupted by show).
    var imeAnimations = 0

    fun boundsInRoot(view: View): Rect =
        Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }

    fun currentImeBottom(): Int =
        ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    fun lift(imeBottom: Int) {
        val target = anchor()?.takeIf { it.isShown && it.height > 0 }
        val shift = if (target == null || imeBottom <= 0) {
            0
        } else {
            val location = IntArray(2)
            root.getLocationInWindow(location)
            val rootTop = location[1] - root.translationY.toInt()
            val keyboardTop = root.rootView.height - imeBottom - rootTop
            val needed = boundsInRoot(target).bottom + gap - keyboardTop
            val allowed = field()?.takeIf { it.isShown }?.let { boundsInRoot(it).top - root.paddingTop - gap }
            minOf(needed, allowed ?: needed).coerceAtLeast(0)
        }
        // Content lifted past the top padding would draw under the status bar icons: clip it at
        // the padded top edge (local coordinates, so offset by the lift).
        val clip = if (shift > 0) Rect(0, root.paddingTop + shift, root.width, root.height + shift) else null
        if (root.translationY == -shift.toFloat() && root.clipBounds == clip) return
        root.translationY = -shift.toFloat()
        root.clipBounds = clip
    }

    ViewCompat.setWindowInsetsAnimationCallback(
        root,
        object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimations++
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat {
                if (imeAnimations > 0) lift(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                imeAnimations = (imeAnimations - 1).coerceAtLeast(0)
                if (imeAnimations == 0) lift(currentImeBottom())
            }
        }
    )
    // Keyboard shown without an animation (it may not cause a layout), or the anchor moved
    // (mode or step switch, resize). Cheap, and a no-op once the translation is right.
    root.viewTreeObserver.addOnPreDrawListener {
        if (imeAnimations == 0) lift(currentImeBottom())
        true
    }
}
