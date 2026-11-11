package com.difft.android.chat.focus








internal object FocusOwnedMark {
    private const val SEPARATOR = '|'

    
    fun encode(owner: String, value: String): String = "$owner$SEPARATOR$value"

    
    fun decode(raw: String?, owner: String?): String? {
        if (raw.isNullOrEmpty() || owner.isNullOrEmpty()) return null
        val index = raw.indexOf(SEPARATOR)
        if (index <= 0 || raw.substring(0, index) != owner) return null
        return raw.substring(index + 1).takeIf { it.isNotEmpty() }
    }
}
