package com.difft.android.base.widget

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import com.difft.android.base.R
import com.difft.android.base.utils.RtlUtils

/**
 * author : song yi
 * e-mail : song.yi
 * time   : 2020/05/17 10:18 PM
 * version: 1.0
 * desc   :
 * 自定义输入控件，支持以下功能:
 * 1.对EditText的监听接口类TextWatcher进行封装，便于外部调用
 * 2.通过自定义属性的方式，支持快速清除按钮
 * 3.焦点监听
 */

open class EditableTextView : AppCompatAutoCompleteTextView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attributes: AttributeSet) : super(context, attributes) {
        init(attributes)
    }

    constructor(context: Context, attributes: AttributeSet, style: Int) : super(context, attributes, style) {
        init(attributes)
    }

    // 清除按钮Drawable
    private var mDrawable: Drawable? = null

    // 输入框监听回调
    private var mTextWatchCallback: CommonTextWatcher? = null

    // 是否可快速清除
    private var mCanClear: Boolean = false

    // 输入监听接口
    interface CommonTextWatcher {
        fun afterTextChanged(s: Editable?)
        fun onFocusChange(v: View?, hasFocus: Boolean)
    }

    private fun init(attributes: AttributeSet) {
        val ta = context.obtainStyledAttributes(attributes, R.styleable.EditableTextView)
        //获取是否显示可快速清除按钮
        mCanClear = ta.getBoolean(R.styleable.EditableTextView_clearable, false)
        mDrawable = if (mCanClear) {
            compoundDrawablesRelative[2]
        } else {
            // 若属性中不传，使用默认值
            context?.getDrawable(R.drawable.account_input_clear_icon)
        }

        isFocusableInTouchMode = true

        initView()
    }

    /**
     * 输入初始化
     */
    private fun initView() {
        // 焦点监听
        setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                setClearDrawableVisible(text.toString().isNotEmpty())
            } else {
                setClearDrawableVisible(false)
            }

            mTextWatchCallback?.onFocusChange(view, hasFocus)
        }

        addTextChangedListener(object : TextWatcherImpl() {
            override fun afterTextChanged(s: Editable?) {
                super.afterTextChanged(s)

                if (s != null) {
                    setClearDrawableVisible(s.isNotEmpty())
                }

                mTextWatchCallback?.afterTextChanged(s)
            }
        })

        setClearDrawableVisible(false)
    }

    /**
     * 设置清除按钮是否可见
     *
     * @param isVisible 是否可见
     */
    fun setClearDrawableVisible(isVisible: Boolean) {
        var rightDrawable: Drawable? = when (isVisible) {
            true -> mDrawable
            else -> null;
        }
        // 使用代码设置该控件left, top, right, and bottom处的图标
        setCompoundDrawablesRelative(
            null, null, rightDrawable,
            null
        )
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_UP -> {
                val isClean: Boolean = if (!RtlUtils.isRtl()) (event.x > (width - totalPaddingEnd))
                        && (event.x < (width - paddingEnd)) else
                    (event.x < (paddingEnd + (mDrawable?.intrinsicWidth ?: 0)))
                if (isClean) {
                    setText("")
                }
            }
        }
        // Fix firebase crash: Fatal Exception: java.lang.IllegalArgumentException
        return try {
            super.onTouchEvent(event)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Edit输入监听回调
     *
     * @param callback 回调接口
     */
    public fun setTextWatchCallback(callback: CommonTextWatcher) {
        mTextWatchCallback = callback
    }

    // 默认实现
    open inner class TextWatcherImpl : TextWatcher {

        override fun afterTextChanged(s: Editable?) {
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }
    }


}