package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.ui.messageaction.SelectionMenuSync.HideReason

/**
 * Keeps the text selection alive while the chat list scrolls (#1294):
 * - while the list moves, the menu is hidden and the highlight and handles follow the text;
 * - when the scroll settles (or after a programmatic scroll in the idle state), the bubble and
 *   content bounds are refreshed, the menu is re-anchored to the visible handle rows and shown;
 * - a rebind of the message item (receipts, reactions, translation, link preview) keeps the selection
 *   when the same ViewHolder still renders the same text, and a rebind of an item without text keeps
 *   the menu. The adapter observer fires before the ViewHolder is rebound, and a long-text bubble is
 *   re-truncated by a pre-draw listener of the bind, so the check is posted from the next pre-draw and
 *   runs after that traversal. Until it runs nothing reads the selection against the layout;
 * - when the message view scrolls out of the viewport (or is detached) or its text changes on
 *   rebind, everything is hidden at once and the real dismiss is deferred to the end of the overlay
 *   gesture (or the next frame when no gesture is down). Dismissing synchronously would remove the
 *   overlay from inside the forwarded `RecyclerView.onTouchEvent` (the detach fires during
 *   `scrollBy`), cancelling the user's gesture.
 * - structural adapter changes still dismiss immediately.
 */
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

    /** True while a rebind of the anchor waits for its check; the selection may not match the layout. */
    private val rebindPending: Boolean get() = rebindPosition != RecyclerView.NO_POSITION

    /** True once a dismiss has been requested (anchor gone or text changed); nothing is refreshed afterwards. */
    var isDismissPending = false
        private set

    private val dismissRunnable = Runnable { dismiss() }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (isDismissPending) return
            val idle = recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE
            if (!idle && (dx != 0 || dy != 0)) menuSync.hide(HideReason.SCROLL)
            // The item may already be rebound with a different text; the rebind check catches up.
            if (rebindPending) return
            selection()?.onListScrolled()
            // A programmatic scroll (keyboard resize, scroll to bottom) never reports IDLE afterwards.
            if (idle) settle(onlyIfMoved = true)
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            // A pending rebind check settles itself once it runs in the idle state.
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

        // The payload overload delegates here by default. Most rebinds (receipts, reactions,
        // translation) leave the body text alone, so the text is compared once the bind has rendered.
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            val position = anchorPosition()
            if (position != RecyclerView.NO_POSITION && position in positionStart until positionStart + itemCount) {
                scheduleRebindCheck(position)
            }
        }
    }

    private val rebindCheckRunnable = Runnable { runRebindCheck() }

    // One-shot: the pre-draw after the change follows the layout pass that rebinds the item. The bind
    // registers its truncation pre-draw listener later than this one, so the check is posted to run
    // after the whole traversal.
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

    /** [text] is the bubble's text view; its rendered text is compared on rebind. */
    fun attach(recyclerView: RecyclerView, anchor: View, content: View?, text: TextView?) {
        detach()
        this.recyclerView = recyclerView
        anchorView = anchor
        contentView = content
        textView = text
        // The rendered text (mentions resolved), not the raw message body.
        attachedText = text?.text?.toString()
        anchor.getGlobalVisibleRect(lastBubbleRect)
        (content ?: recyclerView).getGlobalVisibleRect(lastContentRect)
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.adapter?.registerAdapterDataObserver(adapterObserver)
        anchor.addOnAttachStateChangeListener(attachListener)
    }

    /** Removes every listener; the attach listener goes first, so a teardown detach cannot re-enter. */
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
                // Observer might not be registered (adapter swapped)
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

    /** Runs a deferred dismiss once the overlay gesture that was down when it was requested ends. */
    fun onGestureEnd() {
        if (isDismissPending) dismiss()
    }

    private fun anchorPosition(): Int {
        val anchor = anchorView ?: return RecyclerView.NO_POSITION
        // The observer sees positions of the RecyclerView's own adapter.
        return recyclerView?.findContainingViewHolder(anchor)?.absoluteAdapterPosition ?: RecyclerView.NO_POSITION
    }

    /** Arms (or re-arms, for a further change while one is pending) the check for the next pre-draw. */
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
        // A change that landed after the layout pass is bound in the next one.
        if (rv.hasPendingAdapterUpdates()) {
            scheduleRebindCheck(position)
            return
        }
        rebindPosition = RecyclerView.NO_POSITION
        checkRebind(rv, position)
    }

    private fun checkRebind(rv: RecyclerView, position: Int) {
        val anchor = anchorView ?: return
        // A change animation binds a new ViewHolder; the old one fades out and later detaches.
        val boundHolder = rv.findViewHolderForAdapterPosition(position)
        val sameHolder = boundHolder != null && boundHolder === rv.findContainingViewHolder(anchor)
        // Compared in the rendered state, after the bind's truncation, as the snapshot was taken.
        val textChanged = attachedText != null && textView?.text?.toString() != attachedText
        if (!anchor.isAttachedToWindow || !sameHolder || textChanged) {
            requestDismiss("rebind")
            return
        }
        L.i { "[MessageAction] anchor rebound with unchanged content, re-anchoring" }
        // The bubble may have changed height (receipts, reactions), moving the text.
        selection()?.onListScrolled()
        // Mid-scroll the menu stays hidden; the IDLE transition settles.
        if (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) settle(onlyIfMoved = false)
    }

    private fun settle(onlyIfMoved: Boolean) {
        val anchor = anchorView ?: return
        if (!anchor.isAttachedToWindow || !anchor.getGlobalVisibleRect(bubbleRect)) {
            requestDismiss("offscreen")
            return
        }
        (contentView ?: recyclerView)?.getGlobalVisibleRect(contentRect)
        // A list resize moves the content edges without moving the bubble.
        if (onlyIfMoved && bubbleRect == lastBubbleRect && contentRect == lastContentRect) return
        lastBubbleRect.set(bubbleRect)
        lastContentRect.set(contentRect)
        popup()?.updateBounds(bubbleRect, contentRect)
        // A handle pressed while the list settles (a child-consumed DOWN stops a fling) anchors once released.
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
