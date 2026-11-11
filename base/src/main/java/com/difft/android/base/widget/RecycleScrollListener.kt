package com.difft.android.base.widget

import androidx.recyclerview.widget.RecyclerView

class RecycleScrollListener(private var layoutStyle: Int = LAYOUT_STYLE_VERTICAL) :
    RecyclerView.OnScrollListener() {

    companion object {
        const val SCROLL_TOP = 1
        const val SCROLL_MIDDLE = 0
        const val SCROLL_BOTTOM = -1

        const val LAYOUT_STYLE_VERTICAL = 1001
        const val LAYOUT_STYLE_HORIZON = 1002
    }


    private var mOnStateListener: OnScrollStateListener? = null
    private var mOnPositionListener: OnScrollPositionListener? = null
    private var mOnVerticalListener: OnScrollDirectionVerticalListener? = null
    private var mOnHorizonListener: OnScrollDirectionHorizonListener? = null


    public fun setOnScrollStateListener(listener: OnScrollStateListener) {
        mOnStateListener = listener
    }

    public fun setOnScrollPositionListener(listener: OnScrollPositionListener) {
        mOnPositionListener = listener
    }

    public fun setOnScrollDirectionVerticalListener(listener: OnScrollDirectionVerticalListener) {
        mOnVerticalListener = listener
    }

    public fun setOnScrollDirectionHorizonListener(listener: OnScrollDirectionHorizonListener) {
        mOnHorizonListener = listener
    }


    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        mOnStateListener?.let {
            if (recyclerView.scrollState == 0) {
                it.scrollState(false)
            } else {
                it.scrollState(true)
            }
        }

        mOnPositionListener?.let {
            if (layoutStyle == LAYOUT_STYLE_HORIZON) {
                if (!recyclerView.canScrollHorizontally(-1)) {
                    it.scrollPosition(SCROLL_TOP)
                } else if (!recyclerView.canScrollHorizontally(1)) {
                    it.scrollPosition(SCROLL_BOTTOM)
                } else {
                    it.scrollPosition(SCROLL_MIDDLE)
                }
            } else {
                if (!recyclerView.canScrollVertically(-1)) {
                    it.scrollPosition(SCROLL_TOP)
                } else if (!recyclerView.canScrollVertically(1)) {
                    it.scrollPosition(SCROLL_BOTTOM)
                } else {
                    it.scrollPosition(SCROLL_MIDDLE)
                }
            }
        }

        mOnVerticalListener?.let {
            if (dy < 0) {
                it.scrollUp(dy)
            } else if (dy > 0) {
                it.scrollDown(dy)
            }
        }

        mOnHorizonListener?.let {
            if (dx < 0) {
                it.scrollLeft(dx)
            } else if (dx > 0) {
                it.scrollRight(dx)
            }
        }
    }
}


interface OnScrollStateListener {
    
    fun scrollState(isScrolling: Boolean)
}


interface OnScrollPositionListener {
    fun scrollPosition(position: Int)
}

interface OnScrollDirectionVerticalListener {
    fun scrollUp(dy: Int = 0)

    fun scrollDown(dy: Int = 0)
}

interface OnScrollDirectionHorizonListener {

    fun scrollLeft(d: Int = 0)

    fun scrollRight(dx: Int = 0)
}
