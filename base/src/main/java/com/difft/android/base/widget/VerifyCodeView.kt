package com.difft.android.base.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R

class VerifyCodeView : FrameLayout, TextWatcher, View.OnKeyListener, View.OnFocusChangeListener {

    private var mContext: Context? = null
    var mVerityCodeEditTexts: ArrayList<EditText> = ArrayList()
    private var onCodeInputCompleteListener: OnCodeInputCompleteListener? = null



    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        mContext = context
        LayoutInflater.from(context).inflate(R.layout.uikit_verify_code, this)
        context.withStyledAttributes(attrs, R.styleable.uikit_verify_code) {

            var hide = getBoolean(R.styleable.uikit_verify_code_hideInput, false)
            var canDirectInput = getBoolean(R.styleable.uikit_verify_code_canDirectInput, true)





            initView(hide, canDirectInput)
        }
    }

    private fun initView(hide: Boolean, canDirectInput: Boolean) {
        mVerityCodeEditTexts.add(findViewById(R.id.et_verify_code_1))
        mVerityCodeEditTexts.add(findViewById(R.id.et_verify_code_2))
        mVerityCodeEditTexts.add(findViewById(R.id.et_verify_code_3))
        mVerityCodeEditTexts.add(findViewById(R.id.et_verify_code_4))
        mVerityCodeEditTexts.add(findViewById(R.id.et_verify_code_5))
        mVerityCodeEditTexts.add(findViewById(R.id.et_verify_code_6))
        for (et in mVerityCodeEditTexts) {
            et.setOnKeyListener(this)
            et.onFocusChangeListener = this
            et.addTextChangedListener(this)
            et.background = ContextCompat.getDrawable(context, R.drawable.uikit_verify_code_bg)
            if (hide) {
                et.transformationMethod = BiggerDotPasswordTransformationMethod()
                
            }
            if (!canDirectInput) {
                et.isClickable = false
                et.isFocusableInTouchMode = false
                et.isEnabled = false
                et.isFocusable = false
            }
        }
        mVerityCodeEditTexts[0].isFocusable = true




    }

    override fun afterTextChanged(s: Editable?) {
        if (s?.length != 0) {
            focus()
        }
        if (onCodeInputCompleteListener != null) {
            onCodeInputCompleteListener?.onTextChange(getResult())
            if (mVerityCodeEditTexts[mVerityCodeEditTexts.size - 1].text.isNotEmpty()) {
                onCodeInputCompleteListener?.onComplete(getResult())
            }
        }
    }

    override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
    }

    override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
    }

    override fun onKey(
        v: View?,
        keyCode: Int,
        event: KeyEvent
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
            backFocus()
        }
        return false
    }

    override fun onFocusChange(p0: View?, b: Boolean) {
        if (b) {
            focus()
        }
    }

    fun getResult(): String {
        val stringBuffer = StringBuilder()
        for (et in mVerityCodeEditTexts) {
            stringBuffer.append(et.text)
        }
        return stringBuffer.toString()
    }

    
    fun focus() {
        val count = mVerityCodeEditTexts.size
        for ((index, editText) in mVerityCodeEditTexts.withIndex()) {
            if (editText.text.isEmpty()) {
                editText.isCursorVisible = true
                editText.requestFocus()
                return
            } else {
                editText.isCursorVisible = false
                if (index == count - 1) {
                    editText.requestFocus()
                }
            }
        }
    }

    fun backFocus() {
        var editText: EditText
        for (index in mVerityCodeEditTexts.size - 1 downTo 0) {
            editText = mVerityCodeEditTexts[index]
            if (editText.text.isNotEmpty()) {
                editText.setText("")
                editText.isCursorVisible = true
                editText.requestFocus()
                return
            }
        }
    }

























    fun clearInput() {
        for (i in mVerityCodeEditTexts.size - 1 downTo 0) {
            var editText = mVerityCodeEditTexts[i]
            editText.setText("")
            if (i == 0) {
                editText.isCursorVisible = true
                editText.requestFocus()
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        for (et in mVerityCodeEditTexts) {
            et.isEnabled = enabled
        }
    }

    fun setInputListener(listener: OnCodeInputCompleteListener) {
        this.onCodeInputCompleteListener = listener
    }

    fun setInputByIndex(input: String, index: Int): Boolean {
        return if (index >= mVerityCodeEditTexts.size || index < 0) {
            false
        } else {
            mVerityCodeEditTexts[index].setText(input)
            true
        }
    }

    
    fun setInput(input: String) {
        for (et in mVerityCodeEditTexts) {
            if (et.text.toString().isEmpty()) {
                et.setText(input)
                break
            }
        }
    }


    
    fun delInput() {
        for (index in (mVerityCodeEditTexts.size - 1 downTo 0)) {
            if (mVerityCodeEditTexts[index].text.toString().isNotEmpty()) {
                mVerityCodeEditTexts[index].text.clear()
                mVerityCodeEditTexts[index].requestFocus()
                break
            }
        }
    }


    fun setHideInputVisible(visible: Boolean) {
        if (visible) {
            for (et in mVerityCodeEditTexts) {
                et.transformationMethod = HideReturnsTransformationMethod.getInstance();
            }
        } else {
            for (et in mVerityCodeEditTexts) {
                et.transformationMethod = BiggerDotPasswordTransformationMethod();
                

            }
        }
    }










    interface OnCodeInputCompleteListener {
        fun onTextChange(content: String)

        fun onComplete(content: String)
    }


    private class BiggerDotPasswordTransformationMethod : PasswordTransformationMethod() {
        override fun getTransformation(source: CharSequence, view: View): CharSequence {
            return PasswordCharSequence(super.getTransformation(source, view))
        }

        private class PasswordCharSequence(private val sequence: CharSequence) : CharSequence by sequence {
            val dot = '\u2022'
            val bigDot = '●'
            override fun get(index: Int): Char = if (sequence[index] == dot) bigDot else sequence[index]
        }

    }
}