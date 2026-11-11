package com.difft.android.chat.ui

import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.ui.noSmoothScrollToBottom
import com.difft.android.chat.R
import com.difft.android.chat.widget.chatContainerWidthPx
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.floor












object BubbleWidth {
    
    const val MIN_DP = 280
    private const val RATIO = 280f / 402f

    private val trackers: MutableSet<BubbleWidthTracker> = Collections.newSetFromMap(WeakHashMap())

    
    fun maxBubblePx(columnPx: Int, density: Float): Int =
        maxOf((MIN_DP * density).toInt(), floor(columnPx * RATIO).toInt())

    
    fun freeze() {
        trackers.toList().forEach { it.freeze() }
    }

    
    fun unfreeze() {
        trackers.toList().forEach { it.unfreeze() }
    }

    




    fun navigated(list: RecyclerView) {
        trackers.firstOrNull { it.tracks(list) }?.leftBottom()
    }

    internal fun register(tracker: BubbleWidthTracker) {
        trackers += tracker
    }
}


internal fun View.bubbleColumnPx(): Int {
    var ancestor = parent as? View
    while (ancestor != null) {
        if (ancestor is RecyclerView) {
            (ancestor.getTag(R.id.bubble_width_frozen_column) as? Int)?.let { return it }
            break
        }
        ancestor = ancestor.parent as? View
    }
    return chatContainerWidthPx()
}






internal class BubbleWidthTracker(
    private val list: RecyclerView,
    private val adapter: RecyclerView.Adapter<*>,
    
    private val holdMs: Long = HOLD_MS,
) : View.OnLayoutChangeListener {

    private var laidOutWidth = 0
    private var atBottom = true

    
    private var resizedWhileFrozen = false

    
    
    private var settle: Runnable? = null
    private var keepBottom = false

    
    
    private var holdBottom = false
    private var repinPosted = false
    private val release = Runnable { holdBottom = false }
    private val repin = Runnable {
        repinPosted = false
        if (holdBottom && list.canScrollVertically(1)) alignBottom()
    }

    private val scroll = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            
            
            if (dy == 0) return
            atBottom = !recyclerView.canScrollVertically(1)
        }

        
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState != RecyclerView.SCROLL_STATE_IDLE) cancelPin()
        }
    }

    
    
    private val touch = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) cancelPin()
            return false
        }
    }

    fun tracks(list: RecyclerView) = list === this.list

    private fun cancelPin() {
        keepBottom = false
        holdBottom = false
    }

    




    fun leftBottom() {
        cancelPin()
        atBottom = false
    }

    fun install() {
        list.addOnLayoutChangeListener(this)
        list.addOnScrollListener(scroll)
        list.addOnItemTouchListener(touch)
        BubbleWidth.register(this)
    }

    override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
        val width = right - left
        if (laidOutWidth == 0) laidOutWidth = width
        val frozen = list.getTag(R.id.bubble_width_frozen_column) != null
        if (width != laidOutWidth && frozen) {
            resizedWhileFrozen = true
        } else if (width != laidOutWidth) {
            relayout(width)
        } else if (holdBottom) {
            if (list.canScrollVertically(1) && !repinPosted) {
                repinPosted = true
                list.post(repin)
            }
        } else if (settle == null) {
            atBottom = !list.canScrollVertically(1)
        }
    }

    fun freeze() {
        if (list.getTag(R.id.bubble_width_frozen_column) != null || list.width == 0) return
        
        
        settle?.let { pending ->
            list.removeCallbacks(pending)
            settle = null
            resizedWhileFrozen = true
            atBottom = keepBottom
        }
        list.setTag(R.id.bubble_width_frozen_column, list.width - list.paddingLeft - list.paddingRight)
    }

    fun unfreeze() {
        if (list.getTag(R.id.bubble_width_frozen_column) == null) return
        list.setTag(R.id.bubble_width_frozen_column, null)
        if (list.width != laidOutWidth || resizedWhileFrozen) relayout(list.width) else list.requestLayout()
        resizedWhileFrozen = false
    }

    



    private fun relayout(width: Int) {
        laidOutWidth = width
        if (settle == null) keepBottom = atBottom else list.removeCallbacks(settle)
        val rebind = Runnable {
            settle = null
            adapter.notifyItemRangeChanged(0, adapter.itemCount, ChatMessageAdapter.PAYLOAD_WIDTH)
            
            if (keepBottom && adapter.itemCount > 0 && list.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                
                list.noSmoothScrollToBottom()
                holdBottom = true
                list.removeCallbacks(release)
                list.postDelayed(release, holdMs)
            }
        }
        settle = rebind
        list.postDelayed(rebind, SETTLE_MS)
    }

    




    private fun alignBottom() {
        val last = (list.layoutManager as? LinearLayoutManager)?.findViewByPosition(adapter.itemCount - 1)
        if (last == null || list.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            holdBottom = false
            atBottom = !list.canScrollVertically(1)
        } else {
            list.scrollBy(0, (last.bottom - (list.height - list.paddingBottom)).coerceAtLeast(0))
        }
    }

    private companion object {
        
        const val SETTLE_MS = 80L

        const val HOLD_MS = 300L
    }
}


fun ChatMessageAdapter.followBubbleWidth(list: RecyclerView) {
    BubbleWidthTracker(list, this).install()
}
