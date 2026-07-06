package com.difft.android.base.utils

import android.view.View
import androidx.core.text.layoutDirection
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
        return Locale.getDefault().layoutDirection == View.LAYOUT_DIRECTION_RTL
    }
}