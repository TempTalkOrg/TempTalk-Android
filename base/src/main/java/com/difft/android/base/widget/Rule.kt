package com.difft.android.base.widget

import com.difft.android.base.utils.ValidatorUtil

open class Rule : (String?) -> Boolean {
    override fun invoke(p1: String?): Boolean = checkRule(p1)

    open fun checkRule(text: String?): Boolean = false

    /**
     * 非操作符
     * 支持!Rule()操作
     */
    operator fun not() = NotRule(this)

    /**
     * 或操作符
     * 支持 Rule() or Rule()操作
     */
    infix fun or(other: Rule) = OrRule(this, other)

    /**
     * 与操作符
     * val rule1 = Rule()
     * val rule2 = Rule()
     * val rule3 = rule1 and rule2
     * 支持rule的与操作
     */
    infix fun and(other: Rule) = AndRule(this, other)

    open inner class SingleRule(val rule: Rule) : Rule() {
        override fun checkRule(text: String?): Boolean = rule.checkRule(text)
    }

    abstract inner class MultipleRule(val left: Rule, val right: Rule) : Rule()

    inner class AndRule(left: Rule, right: Rule) : MultipleRule(left, right) {
        override fun checkRule(text: String?): Boolean = left.checkRule(text) && right.checkRule(text)
    }

    inner class OrRule(left: Rule, right: Rule) : MultipleRule(left, right) {
        override fun checkRule(text: String?): Boolean = left.checkRule(text) || right.checkRule(text)
    }

    inner class NotRule(rule: Rule) : SingleRule(rule) {
        override fun checkRule(text: String?): Boolean = !rule.checkRule(text)
    }
}

val NotEmpty = object : Rule() {
    override fun checkRule(text: String?): Boolean = text?.isNotEmpty() ?: false
}

val None = object : Rule() {
    override fun checkRule(text: String?): Boolean = true
}

val UNKNOWN = object : Rule() {
    override fun checkRule(text: String?): Boolean = false
}

val Email = RegexRule(Regex(ValidatorUtil.REGEX_EMAIL))

val Password = RegexRule(Regex(ValidatorUtil.REGEX_PASSWORD))

val Mobile = RegexRule(Regex("[0-9]{5,13}"))

val Referral = RegexRule(Regex(ValidatorUtil.REGEX_REFERRAL_ID))

open class RegexRule(private val regex: Regex) : Rule() {
    override fun checkRule(text: String?): Boolean = text?.matches(regex) ?: false
}