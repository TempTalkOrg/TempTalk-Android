package com.difft.android.base.widget

import androidx.recyclerview.widget.RecyclerView

class RecycleScrollListener(private var layoutStyle: Int = LAYOUT_STYLE_VERTICAL) :
    RecyclerView.OnScrollListener() {

    companion object {
        /**
         * SCROLL_TOP      滑动到顶部
         * SCROLL_MIDDLE   滑动到不是顶部不是底部
         * SCROLL_BOTTOM   滑动到底部
         */
        const val SCROLL_TOP = 1
        const val SCROLL_MIDDLE = 0
        const val SCROLL_BOTTOM = -1

        /**
         * LAYOUT_STYLE_VERTICAL 垂直布局
         * LAYOUT_STYLE_HORIZON  水平布局
         */
        const val LAYOUT_STYLE_VERTICAL = 1001
        const val LAYOUT_STYLE_HORIZON = 1002
    }


    /**
     * mOnStateListener     滑动状态监听
     * mOnPositionListener  滑动位置监听
     * mOnVerticalListener  滑动方向监听 - 垂直布局
     * mOnHorizonListener   滑动方向监听 - 水平布局
     */
    private var mOnStateListener: OnScrollStateListener? = null
    private var mOnPositionListener: OnScrollPositionListener? = null
    private var mOnVerticalListener: OnScrollDirectionVerticalListener? = null
    private var mOnHorizonListener: OnScrollDirectionHorizonListener? = null


    /**
     * 设置滑动状态监听
     */
    public fun setOnScrollStateListener(listener: OnScrollStateListener) {
        mOnStateListener = listener
    }

    /**
     * 设置滑动位置监听
     */
    public fun setOnScrollPositionListener(listener: OnScrollPositionListener) {
        mOnPositionListener = listener
    }

    /**
     * 设置滑动方向监听 - 垂直布局
     */
    public fun setOnScrollDirectionVerticalListener(listener: OnScrollDirectionVerticalListener) {
        mOnVerticalListener = listener
    }

    /**
     * 设置滑动方向监听 - 水平布局
     */
    public fun setOnScrollDirectionHorizonListener(listener: OnScrollDirectionHorizonListener) {
        mOnHorizonListener = listener
    }


    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        //滑动状态
        mOnStateListener?.let {
            if (recyclerView.scrollState == 0) {
                //未滑动
                it.scrollState(false)
            } else {
                //滑动中
                it.scrollState(true)
            }
        }

        //滑动位置
        mOnPositionListener?.let {
            if (layoutStyle == LAYOUT_STYLE_HORIZON) {
                //水平布局
                if (!recyclerView.canScrollHorizontally(-1)) {
                    //滑动到顶部
                    it.scrollPosition(SCROLL_TOP)
                } else if (!recyclerView.canScrollHorizontally(1)) {
                    //滑动到底部
                    it.scrollPosition(SCROLL_BOTTOM)
                } else {
                    //滑动到非顶部非底部
                    it.scrollPosition(SCROLL_MIDDLE)
                }
            } else {
                //默认垂直布局
                if (!recyclerView.canScrollVertically(-1)) {
                    //滑动到顶部
                    it.scrollPosition(SCROLL_TOP)
                } else if (!recyclerView.canScrollVertically(1)) {
                    //滑动到底部
                    it.scrollPosition(SCROLL_BOTTOM)
                } else {
                    //滑动到非顶部非底部
                    it.scrollPosition(SCROLL_MIDDLE)
                }
            }
        }

        //滑动方向 - 垂直布局
        mOnVerticalListener?.let {
            if (dy < 0) {
                //向上滑动
                it.scrollUp(dy)
            } else if (dy > 0) {
                //向下滑动
                it.scrollDown(dy)
            }
        }

        //滑动方向 - 水平布局
        mOnHorizonListener?.let {
            if (dx < 0) {
                //向左滑动
                it.scrollLeft(dx)
            } else if (dx > 0) {
                //向右滑动
                it.scrollRight(dx)
            }
        }
    }
}


/**
 * 滑动状态监听 : 滑动/静止
 */
interface OnScrollStateListener {
    /**
     * true :   滑动
     * false :  静止
     */
    fun scrollState(isScrolling: Boolean)
}

/**
 * 滑动位置监听
 *
 * SCROLL_TOP      滑动到顶部
 * SCROLL_MIDDLE   滑动到不是顶部不是底部
 * SCROLL_BOTTOM   滑动到底部
 */
interface OnScrollPositionListener {
    fun scrollPosition(position: Int)
}

/**
 * 滑动方向监听
 *
 * 垂直布局 : 向上/向下
 */
interface OnScrollDirectionVerticalListener {
    //向上滑动
    fun scrollUp(dy: Int = 0)

    //向下滑动
    fun scrollDown(dy: Int = 0)
}

/**
 * 滑动方向监听
 *
 * 水平布局 : 向左/向右
 */
interface OnScrollDirectionHorizonListener {

    //向左滑动
    fun scrollLeft(d: Int = 0)

    //向右滑动
    fun scrollRight(dx: Int = 0)
}
