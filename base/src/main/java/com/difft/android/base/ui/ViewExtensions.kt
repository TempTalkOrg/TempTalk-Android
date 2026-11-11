package com.difft.android.base.ui

import android.content.Context
import android.util.DisplayMetrics
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

fun RecyclerView.noSmoothScrollToBottom() {
    val recyclerView = this
    val lastPosition = recyclerView.adapter?.itemCount?.minus(1) ?: 0

    recyclerView.scrollToPosition(lastPosition)

    
    recyclerView.doOnNextLayout {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager

        layoutManager?.let { lm ->
            val lastChild = lm.findViewByPosition(lastPosition)

            if (lastChild != null) {
                val itemBottom = lastChild.bottom
                val recyclerViewHeight = recyclerView.height - recyclerView.paddingBottom

                val offset = itemBottom - recyclerViewHeight

                if (offset > 0) {
                    
                    recyclerView.post { recyclerView.scrollBy(0, offset) }
                }
            }
        }
    }
}

fun RecyclerView.isScrolledToBottom(): Boolean {
    val layoutManager = this.layoutManager as? LinearLayoutManager ?: return false
    val lastVisibleItemPosition = layoutManager.findLastCompletelyVisibleItemPosition()
    val totalItemCount = layoutManager.itemCount
    return lastVisibleItemPosition == totalItemCount - 1
}

fun RecyclerView.smoothScrollToPositionWithHelper(context: Context, position: Int, speed: Float = 50f) {
    val layoutManager = this.layoutManager as? LinearLayoutManager ?: return

    val smoothScroller = object : LinearSmoothScroller(context) {
        override fun getVerticalSnapPreference(): Int {
            return SNAP_TO_START
        }

        override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
            return speed / displayMetrics.densityDpi 
        }

        
        override fun calculateTimeForScrolling(dx: Int): Int {
            val base = super.calculateTimeForScrolling(dx)
            return base.coerceAtMost(1000)
        }
    }
    smoothScroller.targetPosition = position
    layoutManager.startSmoothScroll(smoothScroller)
}


fun RecyclerView.fastSmoothScrollToBottom() {
    val layoutManager = layoutManager as? LinearLayoutManager ?: return
    val lastPosition = adapter?.itemCount?.minus(1) ?: return

    val smoothScroller = object : LinearSmoothScroller(context) {
        override fun getVerticalSnapPreference(): Int = SNAP_TO_END

        override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
            return 5f / displayMetrics.densityDpi
        }
    }
    smoothScroller.targetPosition = lastPosition
    layoutManager.startSmoothScroll(smoothScroller)
}
