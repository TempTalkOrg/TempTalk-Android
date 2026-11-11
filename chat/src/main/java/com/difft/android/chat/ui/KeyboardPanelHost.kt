package com.difft.android.chat.ui

import com.difft.android.base.widget.InsetAwareConstraintLayout


internal const val CHAT_PANEL_ANIM_DURATION_MS = 250L













interface KeyboardPanelHost {

    
    fun addKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener)

    
    fun removeKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener)

    



    fun freezeKeyboardPadding()

    
    fun releaseKeyboardPaddingFreeze()

    




    fun onChatPanelVisibilityChanged(visible: Boolean, panelHeightPx: Int)
}




internal class InsetAwareKeyboardPanelHost(
    private val layout: InsetAwareConstraintLayout
) : KeyboardPanelHost {

    override fun addKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) =
        layout.addKeyboardStateListener(listener)

    override fun removeKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) =
        layout.removeKeyboardStateListener(listener)

    override fun freezeKeyboardPadding() = layout.freezeKeyboardPadding()

    override fun releaseKeyboardPaddingFreeze() = layout.releaseKeyboardPaddingFreeze()

    



    override fun onChatPanelVisibilityChanged(visible: Boolean, panelHeightPx: Int) = Unit
}
