package com.difft.android.chat.ui.messageaction

import com.difft.android.base.log.lumberjack.L






internal class SelectionMenuSync(
    private val popup: () -> MessageActionPopup?,
    private val selection: () -> TextSelectionManager?,
) {
    enum class HideReason { DRAG, SCROLL }

    private val hideReasons = mutableSetOf<HideReason>()

    fun isHiddenFor(reason: HideReason): Boolean = reason in hideReasons

    
    fun reanchor() {
        val p = popup() ?: return
        val rows = selection()?.getVisibleHandleRowRects() ?: SelectionRowRects.NONE
        L.i { "[MessageAction] reanchor startRow=${rows.start != null} endRow=${rows.end != null}" }
        p.updateSelectionRows(rows)
    }

    
    fun hide(reason: HideReason) {
        if (!hideReasons.add(reason)) return
        if (reason == HideReason.SCROLL) L.i { "[MessageAction] menu hidden for list scroll" }
        popup()?.setMenuVisible(false)
    }

    
    fun show(reason: HideReason) {
        hideReasons.remove(reason)
        if (hideReasons.isEmpty()) popup()?.setMenuVisible(true)
    }

    val dragCallback: TextSelectionManager.HandleDragCallback = object : TextSelectionManager.HandleDragCallback {
        
        override fun onHandleDrag(screenY: Float, isStart: Boolean) = Unit

        override fun onHandleDragStarted(isStart: Boolean) {
            if (isHiddenFor(HideReason.DRAG)) return
            L.i { "[MessageAction] menu hidden for handle drag isStart=$isStart" }
            hide(HideReason.DRAG)
        }

        override fun onHandleDragEnd() {
            
            show(HideReason.DRAG)
        }
    }

    fun reset() {
        hideReasons.clear()
    }
}
