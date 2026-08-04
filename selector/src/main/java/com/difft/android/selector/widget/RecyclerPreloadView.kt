package com.difft.android.selector.widget

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.selector.interfaces.OnRecyclerViewPreloadMoreListener
import com.difft.android.selector.interfaces.OnRecyclerViewScrollListener
import com.difft.android.selector.interfaces.OnRecyclerViewScrollStateListener

class RecyclerPreloadView : RecyclerView {
    private var isInTheBottom = false
    var isEnabledLoadMore = false
    private var mFirstVisiblePosition = 0
    private var mLastVisiblePosition = 0

    /**
     * reachBottomRow = 1;(default)
     * mean : when the lastVisibleRow is lastRow , call the onReachBottom();
     * reachBottomRow = 2;
     * mean : when the lastVisibleRow is Penultimate Row , call the onReachBottom();
     * And so on
     */
    private var reachBottomRow = BOTTOM_DEFAULT

    private var onRecyclerViewPreloadListener: OnRecyclerViewPreloadMoreListener? = null
    private var onRecyclerViewScrollStateListener: OnRecyclerViewScrollStateListener? = null
    private var onRecyclerViewScrollListener: OnRecyclerViewScrollListener? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    fun setReachBottomRow(reachBottomRow: Int) {
        var row = reachBottomRow
        if (row < 1) {
            row = 1
        }
        this.reachBottomRow = row
    }

    fun getFirstVisiblePosition(): Int = mFirstVisiblePosition

    fun getLastVisiblePosition(): Int = mLastVisiblePosition

    fun setLastVisiblePosition(position: Int) {
        this.mLastVisiblePosition = position
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        val manager = layoutManager ?: throw RuntimeException("LayoutManager is null,Please check it!")
        setLayoutManagerPosition(manager)
        val preloadListener = onRecyclerViewPreloadListener
        if (preloadListener != null) {
            if (isEnabledLoadMore) {
                val currentAdapter = adapter ?: throw RuntimeException("Adapter is null,Please check it!")
                var isReachBottom = false
                if (manager is GridLayoutManager) {
                    val rowCount = currentAdapter.itemCount / manager.spanCount
                    val lastVisibleRowPosition = manager.findLastVisibleItemPosition() / manager.spanCount
                    isReachBottom = lastVisibleRowPosition >= rowCount - reachBottomRow
                }

                if (!isReachBottom) {
                    isInTheBottom = false
                } else if (!isInTheBottom) {
                    preloadListener.onRecyclerViewPreloadMore()
                    if (dy > 0) {
                        isInTheBottom = true
                    }
                } else {
                    // First on-screen with no scroll and content within one screen; ensures a
                    // second pull-to-load when the page size is too small to fill the screen.
                    if (dy == 0) {
                        isInTheBottom = false
                    }
                }
            }
        }

        onRecyclerViewScrollListener?.onScrolled(dx, dy)

        val scrollStateListener = onRecyclerViewScrollStateListener
        if (scrollStateListener != null) {
            if (Math.abs(dy) < LIMIT) {
                scrollStateListener.onScrollSlow()
            } else {
                scrollStateListener.onScrollFast()
            }
        }
    }

    private fun setLayoutManagerPosition(layoutManager: RecyclerView.LayoutManager?) {
        if (layoutManager is GridLayoutManager) {
            mFirstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
            mLastVisiblePosition = layoutManager.findLastVisibleItemPosition()
        } else if (layoutManager is LinearLayoutManager) {
            mFirstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
            mLastVisiblePosition = layoutManager.findLastVisibleItemPosition()
        }
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        if (state == RecyclerView.SCROLL_STATE_IDLE || state == RecyclerView.SCROLL_STATE_DRAGGING) {
            setLayoutManagerPosition(layoutManager)
        }

        onRecyclerViewScrollListener?.onScrollStateChanged(state)

        if (state == RecyclerView.SCROLL_STATE_IDLE) {
            onRecyclerViewScrollStateListener?.onScrollSlow()
        }
    }

    fun setOnRecyclerViewPreloadListener(listener: OnRecyclerViewPreloadMoreListener) {
        this.onRecyclerViewPreloadListener = listener
    }

    fun setOnRecyclerViewScrollStateListener(listener: OnRecyclerViewScrollStateListener) {
        this.onRecyclerViewScrollStateListener = listener
    }

    fun setOnRecyclerViewScrollListener(listener: OnRecyclerViewScrollListener) {
        this.onRecyclerViewScrollListener = listener
    }

    companion object {
        private const val BOTTOM_DEFAULT = 1
        const val BOTTOM_PRELOAD = 2
        private const val LIMIT = 150
    }
}
