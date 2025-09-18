package com.difft.android.base.ui

import android.content.Context
import android.util.DisplayMetrics
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

fun RecyclerView.noSmoothScrollToBottom() {
    val recyclerView = this
    // Get the position of the last item
    val lastPosition = recyclerView.adapter?.itemCount?.minus(1) ?: 0

// Scroll to the last position without animation
    recyclerView.scrollToPosition(lastPosition)

// Post a Runnable to adjust the scroll after the layout pass
    recyclerView.post {
        // Ensure the LayoutManager is a LinearLayoutManager
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager

        layoutManager?.let { lm ->
            // Find the last item's View
            val lastChild = lm.findViewByPosition(lastPosition)

            if (lastChild != null) {
                val itemBottom = lastChild.bottom
                val recyclerViewHeight = recyclerView.height - recyclerView.paddingBottom

                // Calculate the offset needed to bring the bottom of the last item into view
                val offset = itemBottom - recyclerViewHeight

                if (offset > 0) {
                    // Scroll by the offset without animation
                    recyclerView.scrollBy(0, offset)
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
            return SNAP_TO_START // 滚动到顶部对齐
        }

        // 调整滚动速度
        override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
            return speed / displayMetrics.densityDpi // 每像素滚动的时间，值越大滚动越慢
        }

        // 限制最大滚动时间（防止过长距离耗时太久）
        override fun calculateTimeForScrolling(dx: Int): Int {
            val base = super.calculateTimeForScrolling(dx)
            return base.coerceAtMost(1000) // 最多滚动 1000 毫秒
        }
    }
    smoothScroller.targetPosition = position
    layoutManager.startSmoothScroll(smoothScroller)
}

//最后一个item过长时，scrollToPosition只会滚动到顶部对齐，利用此方法可以滚动到底部
fun RecyclerView.fastSmoothScrollToBottom() {
    val layoutManager = layoutManager as? LinearLayoutManager ?: return
    val lastPosition = adapter?.itemCount?.minus(1) ?: return

    val smoothScroller = object : LinearSmoothScroller(context) {
        override fun getVerticalSnapPreference(): Int = SNAP_TO_END

        // 控制滚动速度（值越小越快）
        override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
            return 5f / displayMetrics.densityDpi
        }
    }
    smoothScroller.targetPosition = lastPosition
    layoutManager.startSmoothScroll(smoothScroller)
}