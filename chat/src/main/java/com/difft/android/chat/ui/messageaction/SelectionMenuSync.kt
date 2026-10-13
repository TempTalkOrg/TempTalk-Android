package com.difft.android.chat.ui.messageaction

import com.difft.android.base.log.lumberjack.L

/**
 * Keeps the message action menu anchored to the live text selection and hidden while a selection
 * handle is dragged or the list scrolls (#1294). The menu is visible iff no [HideReason] is set.
 * Providers are read on every call, so late calls after dismiss are no-ops.
 */
internal class SelectionMenuSync(
    private val popup: () -> MessageActionPopup?,
    private val selection: () -> TextSelectionManager?,
) {
    enum class HideReason { DRAG, SCROLL }

    private val hideReasons = mutableSetOf<HideReason>()

    fun isHiddenFor(reason: HideReason): Boolean = reason in hideReasons

    /** Re-anchor to the current handle rows; [SelectionRowRects.NONE] falls back to the bubble. */
    fun reanchor() {
        val p = popup() ?: return
        val rows = selection()?.getVisibleHandleRowRects() ?: SelectionRowRects.NONE
        L.i { "[MessageAction] reanchor startRow=${rows.start != null} endRow=${rows.end != null}" }
        p.updateSelectionRows(rows)
    }

    /** Adds [reason] and hides the menu; a reason that is already set is a no-op. */
    fun hide(reason: HideReason) {
        if (!hideReasons.add(reason)) return
        if (reason == HideReason.SCROLL) L.i { "[MessageAction] menu hidden for list scroll" }
        popup()?.setMenuVisible(false)
    }

    /** Clears [reason]; the menu is shown only once no other reason keeps it hidden. */
    fun show(reason: HideReason) {
        hideReasons.remove(reason)
        if (hideReasons.isEmpty()) popup()?.setMenuVisible(true)
    }

    val dragCallback: TextSelectionManager.HandleDragCallback = object : TextSelectionManager.HandleDragCallback {
        // Fires on every MOVE without slop, so it is not a drag-start signal.
        override fun onHandleDrag(screenY: Float, isStart: Boolean) = Unit

        override fun onHandleDragStarted(isStart: Boolean) {
            if (isHiddenFor(HideReason.DRAG)) return
            L.i { "[MessageAction] menu hidden for handle drag isStart=$isStart" }
            hide(HideReason.DRAG)
        }

        override fun onHandleDragEnd() {
            // Rows and menu mode were already written by the SelectionCallback in this same dispatch.
            show(HideReason.DRAG)
        }
    }

    fun reset() {
        hideReasons.clear()
    }
}
