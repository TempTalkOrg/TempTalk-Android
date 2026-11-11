package com.difft.android.chat.ui

import com.difft.android.base.widget.InsetAwareConstraintLayout
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config









@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InsetAwareKeyboardPanelHostTest {

    private val layout = mockk<InsetAwareConstraintLayout>(relaxed = true)
    private val host = InsetAwareKeyboardPanelHost(layout)

    @Test
    fun `H1 every delegating method forwards exactly once with the same listener instance`() {
        val listener = object : InsetAwareConstraintLayout.KeyboardStateListener {}
        val added = slot<InsetAwareConstraintLayout.KeyboardStateListener>()
        val removed = slot<InsetAwareConstraintLayout.KeyboardStateListener>()

        host.addKeyboardStateListener(listener)
        host.removeKeyboardStateListener(listener)
        host.freezeKeyboardPadding()
        host.releaseKeyboardPaddingFreeze()

        verify(exactly = 1) { layout.addKeyboardStateListener(capture(added)) }
        verify(exactly = 1) { layout.removeKeyboardStateListener(capture(removed)) }
        verify(exactly = 1) { layout.freezeKeyboardPadding() }
        verify(exactly = 1) { layout.releaseKeyboardPaddingFreeze() }
        confirmVerified(layout)

        
        
        assertSame(listener, added.captured)
        assertSame(listener, removed.captured)
    }

    @Test
    fun `H2 onChatPanelVisibilityChanged does not touch the wrapped layout at all`() {
        host.onChatPanelVisibilityChanged(true, 500)
        host.onChatPanelVisibilityChanged(false, 0)

        
        
        confirmVerified(layout)
    }
}
