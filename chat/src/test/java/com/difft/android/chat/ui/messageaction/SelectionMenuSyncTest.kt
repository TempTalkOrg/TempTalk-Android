package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * SM1-SM5 (#1294): [SelectionMenuSync] hides the menu once per handle drag or list scroll (visible
 * iff no reason is set), re-anchors to the selection rows with a NONE fallback, and tolerates late calls after dismiss.
 * Plain JVM: rows use a mocked [Rect], never a real android.graphics.Rect.
 */
class SelectionMenuSyncTest {

    private val popup = mockk<MessageActionPopup>(relaxed = true)
    private val selection = mockk<TextSelectionManager>()

    @Test
    fun `SM1 drag start hides the menu once and drag end shows it again`() {
        val sync = SelectionMenuSync({ popup }, { selection })

        sync.dragCallback.onHandleDragStarted(isStart = false)
        sync.dragCallback.onHandleDragStarted(isStart = false)
        sync.dragCallback.onHandleDragEnd()

        verify(exactly = 1) { popup.setMenuVisible(false) }
        verify(exactly = 1) { popup.setMenuVisible(true) }
        verifyOrder {
            popup.setMenuVisible(false)
            popup.setMenuVisible(true)
        }
    }

    @Test
    fun `SM2 reanchor forwards rows, falls back to NONE, and ignores a missing popup`() {
        val endRow = mockk<Rect>()
        val rows = SelectionRowRects(null, endRow)
        every { selection.getVisibleHandleRowRects() } returns rows
        var selectionProvider: TextSelectionManager? = selection
        var popupProvider: MessageActionPopup? = popup
        val sync = SelectionMenuSync({ popupProvider }, { selectionProvider })

        sync.reanchor()
        verify(exactly = 1) { popup.updateSelectionRows(rows) }

        selectionProvider = null
        sync.reanchor()
        verify(exactly = 1) { popup.updateSelectionRows(SelectionRowRects.NONE) }

        popupProvider = null
        sync.reanchor()
        verify(exactly = 2) { popup.updateSelectionRows(any()) }
    }

    @Test
    fun `SM3 reset clears the hidden-for-drag flag`() {
        val sync = SelectionMenuSync({ popup }, { selection })

        sync.dragCallback.onHandleDragStarted(isStart = true)
        sync.reset()
        sync.dragCallback.onHandleDragStarted(isStart = true)

        verify(exactly = 2) { popup.setMenuVisible(false) }
        verify(exactly = 0) { popup.setMenuVisible(true) }
    }

    @Test
    fun `SM4 the menu shows only once both the drag and the scroll reasons clear`() {
        val sync = SelectionMenuSync({ popup }, { selection })

        sync.dragCallback.onHandleDragStarted(isStart = true)
        sync.hide(SelectionMenuSync.HideReason.SCROLL)
        sync.show(SelectionMenuSync.HideReason.SCROLL)
        verify(exactly = 0) { popup.setMenuVisible(true) }

        sync.dragCallback.onHandleDragEnd()
        verify(exactly = 1) { popup.setMenuVisible(true) }
    }

    @Test
    fun `SM5 a repeated scroll hide hides once and reset clears every reason`() {
        val sync = SelectionMenuSync({ popup }, { selection })

        sync.hide(SelectionMenuSync.HideReason.SCROLL)
        sync.hide(SelectionMenuSync.HideReason.SCROLL)
        verify(exactly = 1) { popup.setMenuVisible(false) }

        sync.dragCallback.onHandleDragStarted(isStart = false)
        sync.reset()
        assertFalse(sync.isHiddenFor(SelectionMenuSync.HideReason.SCROLL))
        assertFalse(sync.isHiddenFor(SelectionMenuSync.HideReason.DRAG))
        sync.hide(SelectionMenuSync.HideReason.SCROLL)
        verify(exactly = 3) { popup.setMenuVisible(false) }
    }
}
