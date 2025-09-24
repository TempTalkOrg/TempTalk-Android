package com.difft.android.base.utils

import java.util.regex.Pattern

object ValidatorUtil {
    /**
     * 正则表达式：验证密码
     */
    const val REGEX_PASSWORD = "^(?=.*[0-9].*)(?=.*[A-Z].*).{8,}$"

    /**
     * 正则表达式：验证邮箱
     */
    const val REGEX_EMAIL =
        "^[a-zA-Z0-9_+-]+(?:\\.[a-zA-Z0-9_+-]+)*@(?:[a-zA-Z0-9-_]+\\.)+[a-zA-Z]+$"

    /**
     * 1.35.0 安全团队给的RefrralID正则
     */
    const val REGEX_REFERRAL_ID =
        "^([0-9]{8,10}|(?=.*[A-Za-z])[A-Za-z0-9_]{4,16})$"

    const val REGEX_PHONE = "[0-9]{5,13}"

    /**
     * 校验密码
     *
     * @return 校验通过返回true，否则返回false
     */
    fun isPassword(password: String): Boolean {
        return Pattern.matches(REGEX_PASSWORD, password)
    }

    /**
     * 校验邮箱
     *
     * @return 校验通过返回true，否则返回false
     */
    fun isEmail(email: String): Boolean {
        return Pattern.matches(REGEX_EMAIL, email)
    }

    /**
     * 校验手机
     *
     * @return 校验通过返回true，否则返回false
     */
    fun isPhone(phone: String): Boolean {
        return Pattern.matches(REGEX_PHONE, phone)
    }

    fun isUidWithVCode(inputString: String): Boolean {
        return inputString.matches("^\\d{17}$".toRegex())
    }

    fun hidePhone(phone: String): String {
        return phone.replace("(\\d{3})\\d{4}(\\d{4})".toRegex(), "$1****$2")
    }

    fun isNumeric(tag: String, startIndex: Int): Boolean {
        val length = tag.length
        if (length == startIndex) {
            return false // no numerals
        }
        for (i in startIndex until length) {
            if (!Character.isDigit(tag[i])) {
                return false
            }
        }
        return true
    }

    fun isUid(uid: String): Boolean {
        if (!uid.startsWith("+")) {
            return false
        }
        val numberPart = uid.substring(1)
        return numberPart.matches(Regex("^[0-9]{5,11}$"))
    }

    fun isGid(gid: String): Boolean {
        if (gid.length != 32) {
            return false
        }
        return gid.matches("^[a-zA-Z0-9]{32}$".toRegex())
    }

    fun isInviteCode(code: String): Boolean {
        if (code.length != 8 && code.length != 32) {
            return false
        }
        return code.matches(Regex("^[a-zA-Z0-9]{8}$|^[a-zA-Z0-9]{32}$"))
    }

    fun isPi(code: String): Boolean {
        if (code.length != 8 && code.length != 32) {
            return false
        }
        return code.matches(Regex("^[a-zA-Z0-9]{8}$|^[a-zA-Z0-9]{32}$"))
    }

}