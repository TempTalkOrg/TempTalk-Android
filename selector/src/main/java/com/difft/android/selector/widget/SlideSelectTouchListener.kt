package com.difft.android.selector.widget

import android.content.Context
import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L

class SlideSelectTouchListener : RecyclerView.OnItemTouchListener {
    var isActive: Boolean = false
    private var mStart = 0
    private var mEnd = 0
    private var mInTopSpot = false
    private var mInBottomSpot = false
    private var mScrollDistance = 0
    private var mLastX = 0f
    private var mLastY = 0f
    private var mLastStart = 0
    private var mLastEnd = 0

    private var mSelectListener: OnSlideSelectListener? = null
    private var mRecyclerView: RecyclerView? = null
    private var mScroller: OverScroller? = null
    private val mScrollRunnable: Runnable = Runnable {
        val scroller = mScroller
        if (scroller != null && scroller.computeScrollOffset()) {
            scrollBy(mScrollDistance)
            ViewCompat.postOnAnimation(mRecyclerView!!, mScrollRunnable)
        }
    }

    // Definitions for touch auto scroll regions
    private var mTopBoundFrom = 0
    private var mTopBoundTo = 0
    private var mBottomBoundFrom = 0
    private var mBottomBoundTo = 0

    // User settings - default values
    private val mMaxScrollDistance = 16
    private val mAutoScrollDistance = (Resources.getSystem().displayMetrics.density * 56).toInt()
    private val mTouchRegionTopOffset = 0
    private val mTouchRegionBottomOffset = 0
    private val mScrollAboveTopRegion = true
    private val mScrollBelowTopRegion = true
    private var mHeaderViewCount = 0

    init {
        reset()
    }

    /** Recyclerview header item count */
    fun setRecyclerViewHeaderCount(count: Int): SlideSelectTouchListener {
        this.mHeaderViewCount = count
        return this
    }

    /** sets the listener that will be notified when items are (un)selected */
    fun withSelectListener(selectListener: OnSlideSelectListener?): SlideSelectTouchListener {
        this.mSelectListener = selectListener
        return this
    }

    /** start the drag selection at the given first-selected item index */
    fun startSlideSelection(position: Int) {
        isActive = true
        mStart = position
        mEnd = position
        mLastStart = position
        mLastEnd = position
        val listener = mSelectListener
        if (listener is OnAdvancedSlideSelectListener) {
            listener.onSelectionStarted(position)
        }
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!isActive || rv.adapter == null || rv.adapter!!.itemCount == 0) {
            return false
        }
        val action = e.action
        when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> reset()
        }

        mRecyclerView = rv
        val height = rv.height
        mTopBoundFrom = mTouchRegionTopOffset
        mTopBoundTo = mTouchRegionTopOffset + mAutoScrollDistance
        mBottomBoundFrom = height + mTouchRegionBottomOffset - mAutoScrollDistance
        mBottomBoundTo = height + mTouchRegionBottomOffset
        return true
    }

    fun startAutoScroll() {
        val rv = mRecyclerView ?: return
        initScroller(rv.context)
        val scroller = mScroller ?: return
        if (scroller.isFinished) {
            rv.removeCallbacks(mScrollRunnable)
            scroller.startScroll(0, scroller.currY, 0, 5000, 100000)
            ViewCompat.postOnAnimation(rv, mScrollRunnable)
        }
    }

    private fun initScroller(context: Context) {
        if (mScroller == null) {
            mScroller = OverScroller(context, LinearInterpolator())
        }
    }

    fun stopAutoScroll() {
        try {
            val scroller = mScroller
            if (scroller != null && !scroller.isFinished) {
                mRecyclerView!!.removeCallbacks(mScrollRunnable)
                scroller.abortAnimation()
            }
        } catch (e: Exception) {
            L.w(e) { "[SlideSelectTouchListener] reset error:" }
        }
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!isActive) {
            reset()
            return
        }

        val action = e.action
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                if (!mInTopSpot && !mInBottomSpot) {
                    changeSelectedRange(rv, e)
                }
                processAutoScroll(e)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> reset()
        }
    }

    private fun changeSelectedRange(rv: RecyclerView, e: MotionEvent) {
        changeSelectedRange(rv, e.x, e.y)
    }

    private fun changeSelectedRange(rv: RecyclerView, x: Float, y: Float) {
        val child = rv.findChildViewUnder(x, y)
        if (child != null) {
            val position = rv.getChildAdapterPosition(child) - mHeaderViewCount
            if (position != RecyclerView.NO_POSITION && mEnd != position) {
                mEnd = position
                notifySelectRangeChange()
            }
        }
    }

    private fun processAutoScroll(event: MotionEvent) {
        val y = event.y.toInt()
        if (y >= mTopBoundFrom && y <= mTopBoundTo) {
            mLastX = event.x
            mLastY = event.y
            val mScrollSpeedFactor =
                ((mTopBoundTo.toFloat() - mTopBoundFrom.toFloat()) - (y.toFloat() - mTopBoundFrom.toFloat())) / (mTopBoundTo.toFloat() - mTopBoundFrom.toFloat())
            mScrollDistance = (mMaxScrollDistance.toFloat() * mScrollSpeedFactor * -1f).toInt()
            if (!mInTopSpot) {
                mInTopSpot = true
                startAutoScroll()
            }
        } else if (mScrollAboveTopRegion && y < mTopBoundFrom) {
            mLastX = event.x
            mLastY = event.y
            mScrollDistance = mMaxScrollDistance * -1
            if (!mInTopSpot) {
                mInTopSpot = true
                startAutoScroll()
            }
        } else if (y >= mBottomBoundFrom && y <= mBottomBoundTo) {
            mLastX = event.x
            mLastY = event.y
            val mScrollSpeedFactor =
                (y.toFloat() - mBottomBoundFrom.toFloat()) / (mBottomBoundTo.toFloat() - mBottomBoundFrom.toFloat())
            mScrollDistance = (mMaxScrollDistance.toFloat() * mScrollSpeedFactor).toInt()
            if (!mInBottomSpot) {
                mInBottomSpot = true
                startAutoScroll()
            }
        } else if (mScrollBelowTopRegion && y > mBottomBoundTo) {
            mLastX = event.x
            mLastY = event.y
            mScrollDistance = mMaxScrollDistance
            if (!mInTopSpot) {
                mInTopSpot = true
                startAutoScroll()
            }
        } else {
            mInBottomSpot = false
            mInTopSpot = false
            mLastX = Float.MIN_VALUE
            mLastY = Float.MIN_VALUE
            stopAutoScroll()
        }
    }

    private fun notifySelectRangeChange() {
        val listener = mSelectListener ?: return
        if (mStart == RecyclerView.NO_POSITION || mEnd == RecyclerView.NO_POSITION) {
            return
        }

        val newStart = Math.min(mStart, mEnd)
        val newEnd = Math.max(mStart, mEnd)
        if (newStart < 0) {
            return
        }
        if (mLastStart == RecyclerView.NO_POSITION || mLastEnd == RecyclerView.NO_POSITION) {
            if (newEnd - newStart == 1) {
                listener.onSelectChange(newStart, newStart, true)
            } else {
                listener.onSelectChange(newStart, newEnd, true)
            }
        } else {
            if (newStart > mLastStart) {
                listener.onSelectChange(mLastStart, newStart - 1, false)
            } else if (newStart < mLastStart) {
                listener.onSelectChange(newStart, mLastStart - 1, true)
            }

            if (newEnd > mLastEnd) {
                listener.onSelectChange(mLastEnd + 1, newEnd, true)
            } else if (newEnd < mLastEnd) {
                listener.onSelectChange(newEnd + 1, mLastEnd, false)
            }
        }

        mLastStart = newStart
        mLastEnd = newEnd
    }

    private fun reset() {
        isActive = false
        val listener = mSelectListener
        if (listener is OnAdvancedSlideSelectListener) {
            listener.onSelectionFinished(mEnd)
        }
        mStart = RecyclerView.NO_POSITION
        mEnd = RecyclerView.NO_POSITION
        mLastStart = RecyclerView.NO_POSITION
        mLastEnd = RecyclerView.NO_POSITION
        mInTopSpot = false
        mInBottomSpot = false
        mLastX = Float.MIN_VALUE
        mLastY = Float.MIN_VALUE
        stopAutoScroll()
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
    }

    private fun scrollBy(distance: Int) {
        val scrollDistance = if (distance > 0) {
            Math.min(distance, mMaxScrollDistance)
        } else {
            Math.max(distance, -mMaxScrollDistance)
        }
        mRecyclerView!!.scrollBy(0, scrollDistance)
        if (mLastX != Float.MIN_VALUE && mLastY != Float.MIN_VALUE) {
            changeSelectedRange(mRecyclerView!!, mLastX, mLastY)
        }
    }

    interface OnAdvancedSlideSelectListener : OnSlideSelectListener {
        fun onSelectionStarted(start: Int)

        fun onSelectionFinished(end: Int)
    }

    interface OnSlideSelectListener {
        fun onSelectChange(start: Int, end: Int, isSelected: Boolean)
    }
}
