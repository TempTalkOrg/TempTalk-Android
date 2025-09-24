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
import com.difft.android.base.R

class VerifyCodeView : FrameLayout, TextWatcher, View.OnKeyListener, View.OnFocusChangeListener {

    private var mContext: Context? = null
    var mVerityCodeEditTexts: ArrayList<EditText> = ArrayList()
    private var onCodeInputCompleteListener: OnCodeInputCompleteListener? = null
//    private lateinit var verifyCodeTitle: TextView
//    private lateinit var verifyCodeErrorTip: TextView

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        mContext = context
        LayoutInflater.from(context).inflate(R.layout.uikit_verify_code, this)
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.uikit_verify_code)
        var title = typedArray.getString(R.styleable.uikit_verify_code_verifyTitle) ?: ""
//        var errorTip = typedArray.getString(R.styleable.uikit_verify_code_verifyErrorTip) ?: ""
        var hide = typedArray.getBoolean(R.styleable.uikit_verify_code_hideInput, false)
        var canDirectInput = typedArray.getBoolean(R.styleable.uikit_verify_code_canDirectInput, true)

//        verifyCodeErrorTip = findViewById(R.id.tv_verify_code_error_tip)
//        verifyCodeTitle = findViewById(R.id.tv_verify_code_title)
//        verifyCodeTitle.text = title
//        tv_verify_code_error_tip.text = errorTip
        initView(hide, canDirectInput)
        typedArray.recycle()
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
                //et.transformationMethod = PasswordTransformationMethod()
            }
            if (!canDirectInput) {
                et.isClickable = false
                et.isFocusableInTouchMode = false
                et.isEnabled = false
                et.isFocusable = false
            }
        }
        mVerityCodeEditTexts[0].isFocusable = true

//        if (hideTitle) {
//            verifyCodeTitle.isVisible = false
//        }
    }

    override fun afterTextChanged(s: Editable?) {
        if (s?.length != 0) {
            focus()
        }
        if (onCodeInputCompleteListener != null) {
            onCodeInputCompleteListener?.onTextChange(getResult())
            //如果最后一个输入框有字符，则返回结果
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

    /**
     * 获取焦点
     */
    fun focus() {
        val count = mVerityCodeEditTexts.size
        //利用for循环找出还最前面那个还没被输入字符的EditText，并把焦点移交给它。
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
        //循环检测有字符的`editText`，把其置空，并获取焦点。
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

//    fun setErrorTip(tip: String) {
//        verifyCodeErrorTip.text = tip
//    }

    /**
     * 错误状态提示信息和提示
     */
//    fun showErrorStatus(tip: String) {
//        mContext?.let {
//            for (et in mVerityCodeEditTexts){
//                et.setTextColor(ContextCompat.getColor(it,R.color.Color_Error))
//            }
//        }
//        verifyCodeErrorTip.visibility = View.VISIBLE
//        setErrorTip(tip)
//    }

    /**
     * 正常显示信息
     */
//    fun showNormalStatus() {
//        mContext?.let {
//            for (et in mVerityCodeEditTexts){
//                et.setTextColor(it.resources.getColor(R.color.Color_PrimaryText))
//            }
//        }
//        verifyCodeErrorTip.visibility = View.GONE
//        setErrorTip("")
//    }

    /**
     * 清空验证码输入框
     */
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

    /**
     * 设置输入框是否可用
     */
    override fun setEnabled(enabled: Boolean) {
        for (et in mVerityCodeEditTexts) {
            et.isEnabled = enabled
        }
    }

    /**
     * 设置输入监听
     */
    fun setInputListener(listener: OnCodeInputCompleteListener) {
        this.onCodeInputCompleteListener = listener
    }

    /**
     * 设置输入框内容
     */
    fun setInputByIndex(input: String, index: Int): Boolean {
        return if (index >= mVerityCodeEditTexts.size || index < 0) {
            false
        } else {
            mVerityCodeEditTexts[index].setText(input)
            true
        }
    }

    /**
     * 设置输入内容，自动向后移动
     */
    fun setInput(input: String) {
        for (et in mVerityCodeEditTexts) {
            if (et.text.toString().isEmpty()) {
                et.setText(input)
                break
            }
        }
    }


    /**
     * 删除输入内容，自动向前移动
     */
    fun delInput() {
        for (index in (mVerityCodeEditTexts.size - 1 downTo 0)) {
            if (mVerityCodeEditTexts[index].text.toString().isNotEmpty()) {
                mVerityCodeEditTexts[index].text.clear()
                mVerityCodeEditTexts[index].requestFocus()
                break
            }
        }
    }


    /**
     * 设置密码是否可见
     */
    fun setHideInputVisible(visible: Boolean) {
        if (visible) {
            for (et in mVerityCodeEditTexts) {
                et.transformationMethod = HideReturnsTransformationMethod.getInstance();
            }
        } else {
            for (et in mVerityCodeEditTexts) {
                et.transformationMethod = BiggerDotPasswordTransformationMethod();
                //et.transformationMethod = PasswordTransformationMethod()

            }
        }
    }


    /**
     * 设置错误提示信息是否是色障模式
     */
//    fun enableColorBlind(enable: Boolean) {
//        if (enable) {
//            verifyCodeErrorTip.setTextColor(ContextCompat.getColor(context, R.color.Color_Blind_Sell))
//        } else {
//            verifyCodeErrorTip.setTextColor(ContextCompat.getColor(context, R.color.Color_Error))
//        }
//    }

    interface OnCodeInputCompleteListener {
        /**
         * 文本改变
         */
        fun onTextChange(content: String)

        /**
         * 输入完成
         */
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