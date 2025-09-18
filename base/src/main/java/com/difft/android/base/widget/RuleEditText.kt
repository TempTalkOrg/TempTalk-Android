package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import com.difft.android.base.R

/**
 * author : 王贺
 * e-mail : 1093109844wanghe@gmail.com
 * time   : 2020/05/20 1:56 PM
 * version: 1.0
 * desc   :
 */
class RuleEditText : EditableTextView {

    var rule = None

    constructor(context: Context) : super(context)
    constructor(context: Context, attributes: AttributeSet) : super(context, attributes) {
        init(context, attributes)
    }

    constructor(context: Context, attributes: AttributeSet, style: Int) : super(context, attributes, style) {
        init(context, attributes)
    }

    private fun init(context: Context, attributes: AttributeSet) {
        val typedArray = context.obtainStyledAttributes(attributes, R.styleable.RuleEditText)
        val ruleFlags = typedArray.getInt(R.styleable.RuleEditText_rule, TYPE_MASK)
        rule = when (ruleFlags) {
            TYPE_MASK -> {
                typedArray.getString(R.styleable.RuleEditText_rule)?.let { RegexRule(Regex(it)) } ?: None
            }
            else -> {
                //
                var rules = UNKNOWN
                if (TYPE_NONE and ruleFlags != 0) {
                    rules = rule or None
                }
                if (TYPE_NOT_EMPTY and ruleFlags != 0) {
                    rules = rules or NotEmpty
                }
                if (TYPE_EMAIL and ruleFlags != 0) {
                    rules = rules or Email
                }
                if (TYPE_PASSWORD and ruleFlags != 0) {
                    rules = rules or Password
                }
                if (TYPE_MOBILE and ruleFlags != 0) {
                    rules = rules or Mobile
                }
                if (TYPE_REFERRAL and ruleFlags != 0) {
                    rules = rules or Referral
                }

                rules
            }
        }
        typedArray.recycle()
    }

    companion object {
        const val TYPE_MASK = 0x00000000
        const val TYPE_NONE = 0x00000001
        const val TYPE_NOT_EMPTY = 0x00000010
        const val TYPE_EMAIL = 0x00000100
        const val TYPE_PASSWORD = 0x00001000
        const val TYPE_MOBILE = 0x00010000
        const val TYPE_REFERRAL = 0x00100000//推荐人ID
    }

    fun check(): Boolean = rule(text.toString())

}