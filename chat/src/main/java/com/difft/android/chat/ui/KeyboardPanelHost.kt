package com.difft.android.chat.ui

import com.difft.android.base.widget.InsetAwareConstraintLayout

/** Panel show/hide animation duration; single owner for the fragment and the popup sheet. */
internal const val CHAT_PANEL_ANIM_DURATION_MS = 250L

/**
 * Host-side contract for the chat input's keyboard <-> action-panel handshake.
 *
 * Two implementations exist:
 *  - [InsetAwareKeyboardPanelHost] wraps the full-screen root [InsetAwareConstraintLayout],
 *    which already lifts the whole layout via IME bottom padding.
 *  - The popup chat Activities, which lift a BottomSheet instead.
 *
 * All methods are main-thread only and are invoked from the input fragment's view lifecycle
 * (between onViewCreated and onDestroyView). Implementations must tolerate being called with the
 * same state twice and must not touch the calling fragment's views.
 */
interface KeyboardPanelHost {

    /** Register a keyboard-state listener. Implementations must dispatch on the main thread. */
    fun addKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener)

    /** Remove a previously registered listener (identity-based). Must be safe for unknown listeners. */
    fun removeKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener)

    /**
     * Hold the current keyboard-driven bottom offset while a custom panel takes over the slot the
     * keyboard occupied, so the layout does not collapse while the keyboard closes behind it.
     */
    fun freezeKeyboardPadding()

    /** Undo [freezeKeyboardPadding] and apply whatever offset the keyboard state currently implies. */
    fun releaseKeyboardPaddingFreeze()

    /**
     * The chat action panel (`ll_chat_actions`) became visible/hidden at [panelHeightPx].
     * [panelHeightPx] is the panel's intended final height (0 when [visible] is false), reported at
     * the START of the panel's own show/hide animation so a host can animate in lockstep.
     */
    fun onChatPanelVisibilityChanged(visible: Boolean, panelHeightPx: Int)
}

/**
 * [KeyboardPanelHost] backed by the full-screen chat root [InsetAwareConstraintLayout].
 *
 * Every method is a 1:1 synchronous delegation — no added state, no reordering, no extra layout or
 * inset passes — so the full-screen chat behaves exactly as it did before this indirection existed.
 * [InsetAwareConstraintLayout] itself is not modified.
 */
internal class InsetAwareKeyboardPanelHost(
    private val layout: InsetAwareConstraintLayout
) : KeyboardPanelHost {

    override fun addKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) =
        layout.addKeyboardStateListener(listener)

    override fun removeKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) =
        layout.removeKeyboardStateListener(listener)

    override fun freezeKeyboardPadding() = layout.freezeKeyboardPadding()

    override fun releaseKeyboardPaddingFreeze() = layout.releaseKeyboardPaddingFreeze()

    /**
     * Deliberate no-op. On the full-screen path the panel expands into the bottom padding that
     * [freezeKeyboardPadding] is already holding, inside a root that occupies the whole window —
     * there is nothing for the host to resize. Any work here would be a behavior change to the
     * full-screen chat, which this change must not make.
     */
    override fun onChatPanelVisibilityChanged(visible: Boolean, panelHeightPx: Int) = Unit
}
