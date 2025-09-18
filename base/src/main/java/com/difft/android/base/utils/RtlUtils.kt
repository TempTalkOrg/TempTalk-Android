package com.difft.android.base.utils

import android.text.TextUtils
import android.view.View
import java.util.Locale

/**
 * @author  : Yunpeng Wang
 * @email   : yunpeng.wang
 * @time    : 2020/08/17 3:42 PM
 * @version : 1.0
 * @desc    : rtl 工具类
 */
object RtlUtils {

    fun isRtl(): Boolean {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
    }
}