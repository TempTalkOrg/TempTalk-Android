package com.difft.android.chat.ui.messageaction

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * T5-7 — compile-anchored structural guard for [FailedMessageActionPopup.Callbacks].
 *
 * The failed-message menu exposes exactly Resend + Delete, so the callback surface must be exactly
 * `onResend` / `onDelete` / `onDismiss`. The anonymous implementation below overrides those three and
 * nothing else: if an `onInfo` member (or any other abstract method) were added back to the
 * interface, this file would STOP COMPILING — which is the assertion.
 */
class FailedMessageCallbacksContractTest {

    @Test
    fun `callbacks interface is satisfied by resend delete dismiss only`() {
        var resend = 0
        var delete = 0
        var dismiss = 0

        val callbacks = object : FailedMessageActionPopup.Callbacks {
            override fun onResend() {
                resend++
            }

            override fun onDelete() {
                delete++
            }

            override fun onDismiss() {
                dismiss++
            }
        }

        assertNotNull(callbacks)

        // Each member is independently reachable (no accidental delegation between them).
        callbacks.onResend()
        callbacks.onDelete()
        callbacks.onDismiss()
        org.junit.Assert.assertEquals(1, resend)
        org.junit.Assert.assertEquals(1, delete)
        org.junit.Assert.assertEquals(1, dismiss)
    }
}
