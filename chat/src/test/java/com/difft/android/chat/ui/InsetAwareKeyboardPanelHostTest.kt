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

/**
 * H1/H2 — the full-screen adapter must be a pure pass-through.
 *
 * These are the machine-checked half of the zero-delta constraint: the full-screen chat path must
 * behave exactly as it did before [KeyboardPanelHost] existed. H1 pins that the four real methods
 * delegate 1:1 (same call, same listener instance); H2 pins that the one genuinely new method
 * touches the wrapped layout not at all.
 */
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

        // The SAME object must reach the layout's listener list on both sides, or removal in
        // onDestroyView would silently leak the listener.
        assertSame(listener, added.captured)
        assertSame(listener, removed.captured)
    }

    @Test
    fun `H2 onChatPanelVisibilityChanged does not touch the wrapped layout at all`() {
        host.onChatPanelVisibilityChanged(true, 500)
        host.onChatPanelVisibilityChanged(false, 0)

        // No verify() calls recorded, so confirmVerified asserts ZERO interactions: the full-screen
        // path gains no view mutation, no inset pass, no state write from the new host method.
        confirmVerified(layout)
    }
}
