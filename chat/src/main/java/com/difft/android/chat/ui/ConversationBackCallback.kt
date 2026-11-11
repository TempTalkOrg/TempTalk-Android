package com.difft.android.chat.ui

import androidx.activity.OnBackPressedCallback










internal class ConversationBackCallback(
    private val hosted: Boolean,
    private val isMenuShowing: () -> Boolean,
    private val isSelecting: () -> Boolean,
    private val dismissMenu: () -> Unit,
    private val exitSelection: () -> Unit,
    private val closePage: () -> Unit,
    private val passOn: () -> Unit,
) : OnBackPressedCallback(true) {

    fun refreshEnabled() {
        if (hosted) isEnabled = isMenuShowing() || isSelecting()
    }

    override fun handleOnBackPressed() {
        when {
            isMenuShowing() -> {
                dismissMenu()
                refreshEnabled()
            }
            isSelecting() -> exitSelection()
            hosted -> {
                isEnabled = false
                passOn()
            }
            else -> closePage()
        }
    }
}
