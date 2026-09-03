package com.difft.android.chat.scroll

import com.difft.android.base.ui.noSmoothScrollToBottom
import com.difft.android.chat.scroll.testing.RecyclerViewScrollHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Case #13 — FRAMEWORK ASSUMPTION test for `RecyclerView.noSmoothScrollToBottom()`
 * (base `ViewExtensions.kt`), the primitive behind the call-header compensation and every
 * auto-snap-to-bottom path in the chat list.
 *
 * Two-step by design: `scrollToPosition` first, then a `doOnNextLayout` fine-tune that uses
 * post-layout dimensions. This pins both halves — the last row must end up fully inside the
 * viewport, and getting there must not look like a drag.
 */
class NoSmoothScrollToBottomTest : RecyclerViewScrollHarness() {

    @Test
    fun `noSmoothScrollToBottom lands the last row fully inside the viewport`() {
        submitAndLayout(itemCount = ITEM_COUNT)

        recyclerView.noSmoothScrollToBottom()
        layoutRecyclerView()
        idleLooper()

        assertEquals(ITEM_COUNT - 1, lastVisible())
        val lastChild = layoutManager().findViewByPosition(ITEM_COUNT - 1)
        assertNotNull("the last row is not laid out", lastChild)
        assertTrue(
            "last row bottom ${lastChild!!.bottom} overflows the viewport " +
                "${recyclerView.height - recyclerView.paddingBottom}",
            lastChild.bottom <= recyclerView.height - recyclerView.paddingBottom,
        )
        assertNeverDragged()
    }

    private companion object {
        const val ITEM_COUNT = 60
    }
}
