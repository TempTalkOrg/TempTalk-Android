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

/**
 * Z of the message action menu inside [MessageActionOverlayLayout]: one step above the selection
 * handles, so the menu draws above the highlight and handles (#1294).
 *
 * Draw order is controlled by Z, not by child index: software `dispatchDraw` sorts children by Z,
 * and the hardware pipeline reorders RenderNodes by Z. Reordering children or `bringToFront()`
 * does not change it.
 */
internal const val MENU_TRANSLATION_Z_PX = SELECTION_HANDLE_ELEVATION_PX + 1f

/**
 * Full-window overlay hosting the message action menu (child 0) and the text-selection
 * highlight/handles added by `TextSelectionManager`.
 *
 * Touch routing is decided once per ACTION_DOWN (#1294):
 * - DOWN inside the laid-out [menuBounds] of a visible menu goes to the menu before any child, so a
 *   selection handle under the menu cannot steal it;
 * - otherwise the children are asked (handles); a child-consumed DOWN (normally a handle) stops a running
 *   list fling;
 * - otherwise, with a [recyclerView], the gesture scrolls the list: copies of every event go to
 *   `onTouchEvent` only (the DOWN also through `onInterceptTouchEvent`, so item-touch listeners and
 *   a running fling see a fresh DOWN), never to `dispatchTouchEvent`, so no item click, link or
 *   long-press fires. A tap (movement within touch slop, no list scroll) dismisses on ACTION_UP;
 * - without a [recyclerView], the popup is dismissed on ACTION_UP.
 *
 * The menu ComposeView is also the highest-Z child on the child route, so it is offered every DOWN
 * first there; it only lets DOWNs through because its outer full-size Box has no pointer input.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class MessageActionOverlayLayout(
    context: Context,
    private val menuView: View,
    private val onDismissRequest: () -> Unit,
) : FrameLayout(context) {

    private enum class Route { MENU, CHILD, LIST, DISMISS_ON_UP }

    /** Laid-out menu bounds in overlay coordinates; null until placed and during a re-measure. */
    var menuBounds: Rect? = null

    /** The list scrolled by touches outside the menu and handles; null dismisses on any tap instead. */
    var recyclerView: RecyclerView? = null

    /** Called after the UP or CANCEL that ends any routed gesture. */
    var onGestureEnd: (() -> Unit)? = null

    /** True between a routed DOWN and its UP/CANCEL. */
    var isGestureActive = false
        private set

    // Swallow the continuation of the long-press that opened the popup.
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
        // One gesture goes to the child that took the DOWN, so a second finger cannot start a second handle drag (#1294).
        isMotionEventSplittingEnabled = false
        addView(menuView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        menuView.translationZ = MENU_TRANSLATION_Z_PX
    }

    val isMenuVisible: Boolean
        get() = menuView.visibility == View.VISIBLE

    /** INVISIBLE keeps the composition; a hidden menu neither draws nor receives touches. */
    fun setMenuVisible(visible: Boolean) {
        menuView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /** Hides the menu, highlight and handles at once, ahead of a deferred dismiss. */
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
            // The menu owns the whole sequence, even where Compose does not consume (gaps, padding).
            menuView.dispatchTouchEvent(ev)
            return Route.MENU
        }
        if (super.dispatchTouchEvent(ev)) {
            // A handle drag during a fling would chase moving text.
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
        // A tap that only stops a running fling is not a dismiss tap.
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

    /** DOWN inside the list's visible window rect; the toolbar or input bar area forwards nothing. */
    private fun isInsideList(rv: RecyclerView, ev: MotionEvent): Boolean {
        if (!rv.getGlobalVisibleRect(tmpRect)) return false
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
        // The first finger lifting while another stays down ends the tap; the later UP must not dismiss.
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
