package com.difft.android.chat.components

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import com.difft.android.chat.components.KeyboardAwareLinearLayout.OnKeyboardShownListener
import com.difft.android.chat.util.ServiceUtil

open class InputAwareLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : KeyboardAwareLinearLayout(context, attrs, defStyle), OnKeyboardShownListener {

    private var current: InputView? = null

    init {
        addOnKeyboardShownListener(this)
    }

    override fun onKeyboardShown() {
    }

    fun show(imeTarget: EditText, input: InputView) {
        if (isKeyboardOpen()) {
            hideSoftkey(imeTarget) {
                hideAttachedInput(true)
                input.show(getKeyboardHeight(), true)
                current = input
            }
        } else {
            current?.hide(true)
            input.show(getKeyboardHeight(), current != null)
            current = input
        }
    }

    fun getCurrentInput(): InputView? = current

    fun hideCurrentInput(imeTarget: EditText) {
        if (isKeyboardOpen()) hideSoftkey(imeTarget, null)
        else hideAttachedInput(false)
    }

    fun hideAttachedInput(instant: Boolean) {
        current?.hide(instant)
        current = null
    }

    fun isInputOpen(): Boolean =
        isKeyboardOpen() || (current != null && current!!.isShowing())

    fun showSoftkey(inputTarget: EditText) {
        postOnKeyboardOpen { hideAttachedInput(true) }
        inputTarget.post {
            inputTarget.requestFocus()
            ServiceUtil.getInputMethodManager(inputTarget.context).showSoftInput(inputTarget, 0)
        }
    }

    fun hideSoftkey(inputTarget: EditText, runAfterClose: Runnable?) {
        if (runAfterClose != null) postOnKeyboardClose(runAfterClose)

        ServiceUtil.getInputMethodManager(inputTarget.context)
            .hideSoftInputFromWindow(inputTarget.windowToken, 0)
    }

    interface InputView {
        fun show(height: Int, immediate: Boolean)
        fun hide(immediate: Boolean)
        fun isShowing(): Boolean
    }
}
