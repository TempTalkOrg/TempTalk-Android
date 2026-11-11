package com.difft.android.chat.ui.messageaction

import org.junit.Assert.assertNotNull
import org.junit.Test









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

        
        callbacks.onResend()
        callbacks.onDelete()
        callbacks.onDismiss()
        org.junit.Assert.assertEquals(1, resend)
        org.junit.Assert.assertEquals(1, delete)
        org.junit.Assert.assertEquals(1, dismiss)
    }
}
