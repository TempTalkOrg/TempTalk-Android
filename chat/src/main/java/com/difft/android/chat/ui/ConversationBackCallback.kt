package com.difft.android.chat.ui

import androidx.activity.OnBackPressedCallback

/**
 * Back handling of the conversation message list, in order: close the message-action menu, leave
 * selection mode, then (shell or popup only) close the page.
 *
 * Under IndexActivity's detail stack ([hosted]) it has no page-closing branch and is enabled only
 * while the menu shows or selection mode is on (the owner calls [refreshEnabled] on both changes),
 * so ordinary Back reaches the host's callback: pop, expand the list, or send the task back. Should
 * the menu close without a refresh, a stale press is passed on through [passOn].
 */
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
