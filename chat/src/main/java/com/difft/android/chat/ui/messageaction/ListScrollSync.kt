package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.ui.messageaction.SelectionMenuSync.HideReason


















internal class ListScrollSync(
    private val popup: () -> MessageActionPopup?,
    private val selection: () -> TextSelectionManager?,
    private val menuSync: SelectionMenuSync,
    private val dismiss: () -> Unit,
) {
    private var recyclerView: RecyclerView? = null
    private var anchorView: View? = null
    private var contentView: View? = null
    private var textView: TextView? = null
    private var attachedText: String? = null
    private var rebindPosition = RecyclerView.NO_POSITION
    private val bubbleRect = Rect()
    private val lastBubbleRect = Rect()
    private val contentRect = Rect()
    private val lastContentRect = Rect()

    
    private val rebindPending: Boolean get() = rebindPosition != RecyclerView.NO_POSITION

    
    var isDismissPending = false
        private set

    private val dismissRunnable = Runnable { dismiss() }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (isDismissPending) return
            val idle = recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE
            if (!idle && (dx != 0 || dy != 0)) menuSync.hide(HideReason.SCROLL)
            
            if (rebindPending) return
            selection()?.onListScrolled()
            
            if (idle) settle(onlyIfMoved = true)
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            
            if (newState == RecyclerView.SCROLL_STATE_IDLE && !isDismissPending && !rebindPending) {
                settle(onlyIfMoved = false)
            }
        }
    }

    private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = dismiss()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = dismiss()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = dismiss()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = dismiss()

        
        
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            val position = anchorPosition()
            if (position != RecyclerView.NO_POSITION && position in positionStart until positionStart + itemCount) {
                scheduleRebindCheck(position)
            }
        }
    }

    private val rebindCheckRunnable = Runnable { runRebindCheck() }

    
    
    
    private val rebindPreDraw = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            recyclerView?.let { rv ->
                rv.viewTreeObserver.removeOnPreDrawListener(this)
                rv.post(rebindCheckRunnable)
            }
            return true
        }
    }

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = requestDismiss("detached")
    }

    
    fun attach(recyclerView: RecyclerView, anchor: View, content: View?, text: TextView?) {
        detach()
        this.recyclerView = recyclerView
        anchorView = anchor
        contentView = content
        textView = text
        
        attachedText = text?.text?.toString()
        anchor.getGlobalVisibleRect(lastBubbleRect)
        ChatContentBand.onScreen(content ?: recyclerView, lastContentRect)
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.adapter?.registerAdapterDataObserver(adapterObserver)
        anchor.addOnAttachStateChangeListener(attachListener)
    }

    
    fun detach() {
        anchorView?.removeOnAttachStateChangeListener(attachListener)
        recyclerView?.let { rv ->
            rv.removeCallbacks(dismissRunnable)
            rv.removeCallbacks(rebindCheckRunnable)
            rv.viewTreeObserver.removeOnPreDrawListener(rebindPreDraw)
            rv.removeOnScrollListener(scrollListener)
            try {
                rv.adapter?.unregisterAdapterDataObserver(adapterObserver)
            } catch (e: IllegalStateException) {
                
                L.w { "[MessageAction] unregisterAdapterDataObserver failed: ${e.stackTraceToString()}" }
            }
        }
        recyclerView = null
        anchorView = null
        contentView = null
        textView = null
        attachedText = null
        rebindPosition = RecyclerView.NO_POSITION
        isDismissPending = false
    }

    
    fun onGestureEnd() {
        if (isDismissPending) dismiss()
    }

    private fun anchorPosition(): Int {
        val anchor = anchorView ?: return RecyclerView.NO_POSITION
        
        return recyclerView?.findContainingViewHolder(anchor)?.absoluteAdapterPosition ?: RecyclerView.NO_POSITION
    }

    
    private fun scheduleRebindCheck(position: Int) {
        val rv = recyclerView ?: return
        rv.removeCallbacks(rebindCheckRunnable)
        rv.viewTreeObserver.removeOnPreDrawListener(rebindPreDraw)
        rv.viewTreeObserver.addOnPreDrawListener(rebindPreDraw)
        rebindPosition = position
    }

    private fun runRebindCheck() {
        val rv = recyclerView ?: return
        val position = rebindPosition
        if (isDismissPending || position == RecyclerView.NO_POSITION) return
        
        if (rv.hasPendingAdapterUpdates()) {
            scheduleRebindCheck(position)
            return
        }
        rebindPosition = RecyclerView.NO_POSITION
        checkRebind(rv, position)
    }

    private fun checkRebind(rv: RecyclerView, position: Int) {
        val anchor = anchorView ?: return
        
        val boundHolder = rv.findViewHolderForAdapterPosition(position)
        val sameHolder = boundHolder != null && boundHolder === rv.findContainingViewHolder(anchor)
        
        val textChanged = attachedText != null && textView?.text?.toString() != attachedText
        if (!anchor.isAttachedToWindow || !sameHolder || textChanged) {
            requestDismiss("rebind")
            return
        }
        L.i { "[MessageAction] anchor rebound with unchanged content, re-anchoring" }
        
        selection()?.onListScrolled()
        
        if (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) settle(onlyIfMoved = false)
    }

    private fun settle(onlyIfMoved: Boolean) {
        val anchor = anchorView ?: return
        if (!anchor.isAttachedToWindow || !anchor.getGlobalVisibleRect(bubbleRect)) {
            requestDismiss("offscreen")
            return
        }
        val band = contentView ?: recyclerView
        if (band != null) {
            ChatContentBand.onScreen(band, contentRect)
            
            if (!Rect.intersects(bubbleRect, contentRect)) {
                requestDismiss("offscreen")
                return
            }
        }
        
        if (onlyIfMoved && bubbleRect == lastBubbleRect && contentRect == lastContentRect) return
        lastBubbleRect.set(bubbleRect)
        lastContentRect.set(contentRect)
        popup()?.updateBounds(bubbleRect, contentRect)
        
        if (selection()?.isDragging() == true) menuSync.hide(HideReason.DRAG)
        menuSync.reanchor()
        menuSync.show(HideReason.SCROLL)
    }

    private fun requestDismiss(reason: String) {
        if (isDismissPending) return
        isDismissPending = true
        anchorView?.removeOnAttachStateChangeListener(attachListener)
        val p = popup()
        val gestureActive = p?.isGestureActive == true
        L.i { "[MessageAction] deferred dismiss reason=$reason gestureActive=$gestureActive" }
        p?.hideAll()
        if (gestureActive) return
        val rv = recyclerView
        if (rv != null) rv.post(dismissRunnable) else dismiss()
    }
}
