package com.difft.android.chat.ui.messageaction

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot









internal const val MENU_TRANSLATION_Z_PX = SELECTION_HANDLE_ELEVATION_PX + 1f
private const val MENU_FADE_IN_MS = 150L



















@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class MessageActionOverlayLayout(
    context: Context,
    private val menuView: View,
    private val onDismissRequest: () -> Unit,
) : FrameLayout(context) {

    private enum class Route { MENU, CHILD, LIST, DISMISS_ON_UP }

    
    var menuBounds: Rect? = null

    
    var recyclerView: RecyclerView? = null

    
    var onGestureEnd: (() -> Unit)? = null

    
    var isGestureActive = false
        private set

    
    private var waitingForNewTouchSequence = true
    private var route = Route.DISMISS_ON_UP

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downPointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var movedBeyondSlop = false
    private var listScrolled = false
    private var forwardToList = false
    private var listOffsetX = 0f
    private var listOffsetY = 0f
    private val tmpRect = Rect()
    private val tmpLocation = IntArray(2)

    private val gestureScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dx != 0 || dy != 0) listScrolled = true
        }
    }

    init {
        
        isMotionEventSplittingEnabled = false
        addView(menuView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        menuView.translationZ = MENU_TRANSLATION_Z_PX
    }

    val isMenuVisible: Boolean
        get() = menuView.visibility == View.VISIBLE

    
    fun setMenuVisible(visible: Boolean) {
        if (visible && menuView.visibility != View.VISIBLE) {
            
            menuView.alpha = 0f
            menuView.animate().alpha(1f).setDuration(MENU_FADE_IN_MS).start()
        } else if (!visible) {
            menuView.animate().cancel()
            menuView.alpha = 1f
        }
        menuView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    
    fun hideAllChildren() {
        for (i in 0 until childCount) getChildAt(i).visibility = View.INVISIBLE
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (waitingForNewTouchSequence) {
            if (ev.actionMasked != MotionEvent.ACTION_DOWN) return true
            waitingForNewTouchSequence = false
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            isGestureActive = true
            route = routeAndDeliverDown(ev)
            return true
        }
        when (route) {
            Route.MENU -> menuView.dispatchTouchEvent(ev)
            Route.CHILD -> super.dispatchTouchEvent(ev)
            Route.LIST -> deliverToList(ev)
            Route.DISMISS_ON_UP -> if (ev.actionMasked == MotionEvent.ACTION_UP) onDismissRequest()
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            endGesture()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        recyclerView?.removeOnScrollListener(gestureScrollListener)
        isGestureActive = false
        super.onDetachedFromWindow()
    }

    private fun routeAndDeliverDown(ev: MotionEvent): Route {
        val bounds = menuBounds
        if (isMenuVisible && bounds != null && bounds.contains(ev.x.toInt(), ev.y.toInt())) {
            
            menuView.dispatchTouchEvent(ev)
            return Route.MENU
        }
        if (super.dispatchTouchEvent(ev)) {
            
            recyclerView?.stopScroll()
            return Route.CHILD
        }
        val rv = recyclerView ?: return Route.DISMISS_ON_UP
        beginListGesture(rv, ev)
        return Route.LIST
    }

    private fun beginListGesture(rv: RecyclerView, ev: MotionEvent) {
        downPointerId = ev.getPointerId(0)
        downX = ev.x
        downY = ev.y
        movedBeyondSlop = false
        forwardToList = rv.isAttachedToWindow && isInsideList(rv, ev)
        
        listScrolled = forwardToList && rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
        if (!forwardToList) return
        getLocationInWindow(tmpLocation)
        listOffsetX = tmpLocation[0].toFloat()
        listOffsetY = tmpLocation[1].toFloat()
        rv.getLocationInWindow(tmpLocation)
        listOffsetX -= tmpLocation[0]
        listOffsetY -= tmpLocation[1]
        rv.removeOnScrollListener(gestureScrollListener)
        rv.addOnScrollListener(gestureScrollListener)
        forward(rv, ev, isDown = true)
    }

    
    private fun isInsideList(rv: RecyclerView, ev: MotionEvent): Boolean {
        if (!ChatContentBand.onScreen(rv, tmpRect)) return false
        getLocationInWindow(tmpLocation)
        return tmpRect.contains((tmpLocation[0] + ev.x).toInt(), (tmpLocation[1] + ev.y).toInt())
    }

    private fun deliverToList(ev: MotionEvent) {
        trackTap(ev)
        val rv = recyclerView
        if (forwardToList && rv != null && rv.isAttachedToWindow) forward(rv, ev, isDown = false)
        if (ev.actionMasked == MotionEvent.ACTION_UP && !movedBeyondSlop && !listScrolled) onDismissRequest()
    }

    private fun trackTap(ev: MotionEvent) {
        if (movedBeyondSlop) return
        
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP && ev.getPointerId(ev.actionIndex) == downPointerId) {
            movedBeyondSlop = true
            return
        }
        val index = ev.findPointerIndex(downPointerId)
        if (index < 0) return
        movedBeyondSlop = hypot(ev.getX(index) - downX, ev.getY(index) - downY) > touchSlop
    }

    private fun forward(rv: RecyclerView, ev: MotionEvent, isDown: Boolean) {
        val copy = MotionEvent.obtain(ev)
        try {
            copy.offsetLocation(listOffsetX, listOffsetY)
            if (isDown) rv.onInterceptTouchEvent(copy)
            rv.onTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    private fun endGesture() {
        recyclerView?.removeOnScrollListener(gestureScrollListener)
        forwardToList = false
        isGestureActive = false
        onGestureEnd?.invoke()
    }
}
