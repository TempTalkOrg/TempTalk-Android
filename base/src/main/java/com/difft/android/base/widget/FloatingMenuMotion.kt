package com.difft.android.base.widget

import android.view.View
import android.view.animation.PathInterpolator







object FloatingMenuMotion {
    const val COLLAPSED_SCALE = 0.18f
    const val SHOW_MS = 420L
    const val DISMISS_MS = 180L
    private const val ROWS_DELAY_MS = 80L
    private const val ROWS_FADE_MS = 200L
    private const val ROWS_DROP_DP = 4f

    
    fun grow(card: View, rows: View? = null) {
        card.animate().cancel()
        card.scaleX = COLLAPSED_SCALE
        card.scaleY = COLLAPSED_SCALE
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(SHOW_MS).setInterpolator(PathInterpolator(0.3f, 1.3f, 0.5f, 1f)).start()
        rows ?: return
        rows.animate().cancel()
        rows.alpha = 0f
        rows.translationY = ROWS_DROP_DP * card.resources.displayMetrics.density
        rows.animate().alpha(1f).translationY(0f).setStartDelay(ROWS_DELAY_MS).setDuration(ROWS_FADE_MS).start()
    }

    
    fun shrink(card: View, then: () -> Unit) {
        card.animate().cancel()
        card.animate().scaleX(COLLAPSED_SCALE).scaleY(COLLAPSED_SCALE).alpha(0f)
            .setDuration(DISMISS_MS).setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction(then)
            .start()
    }
}
