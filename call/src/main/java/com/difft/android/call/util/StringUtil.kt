package com.difft.android.call.util

object StringUtil {

    fun getShowUserName(userName: String, showLength: Int): String {
        if (userName.isEmpty() || showLength <= 0) {
            return if (showLength > 0 && userName.isNotEmpty()) "..." else userName
        }
        return if (userName.length > showLength) userName.substring(0, showLength) + "..." else userName
    }
}