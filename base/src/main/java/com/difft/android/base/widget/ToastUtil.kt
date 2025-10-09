package com.difft.android.base.widget

import android.widget.Toast
import androidx.annotation.StringRes
import com.difft.android.base.utils.application

/**
 * Toast工具类
 * 使用Application作为上下文，避免页面销毁后无法显示的问题
 * 替代PopTip.show和TipDialog.show的使用
 */
object ToastUtil {

    /**
     * 显示短时间Toast
     * @param message 消息内容
     */
    fun show(message: String) {
        Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示短时间Toast
     * @param messageRes 消息资源ID
     */
    fun show(@StringRes messageRes: Int) {
        Toast.makeText(application, messageRes, Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示长时间Toast
     * @param message 消息内容
     */
    fun showLong(message: String) {
        Toast.makeText(application, message, Toast.LENGTH_LONG).show()
    }

    /**
     * 显示长时间Toast
     * @param messageRes 消息资源ID
     */
    fun showLong(@StringRes messageRes: Int) {
        Toast.makeText(application, messageRes, Toast.LENGTH_LONG).show()
    }
}
