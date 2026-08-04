package com.difft.android.selector.utils

import android.content.Context
import android.text.TextUtils
import android.widget.Toast

import com.difft.android.selector.app.PictureAppMaster
import com.difft.android.selector.thread.PictureThreadUtils

object ToastUtils {

    private const val TIME = 1000L
    private var lastClickTime: Long = 0
    private var mLastText: String? = null

    @JvmStatic
    fun showToast(context: Context, text: String) {
        if (isFastDoubleClick() && TextUtils.equals(text, mLastText)) {
            return
        }
        if (PictureThreadUtils.isInUiThread()) {
            val appContext = PictureAppMaster.getInstance().getAppContext() ?: context.applicationContext
            Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
            mLastText = text
        } else {
            PictureThreadUtils.runOnUiThread {
                val appContext = PictureAppMaster.getInstance().getAppContext() ?: context.applicationContext
                Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
                mLastText = text
            }
        }
    }

    @JvmStatic
    fun isFastDoubleClick(): Boolean {
        val time = System.currentTimeMillis()
        if (time - lastClickTime < TIME) {
            return true
        }
        lastClickTime = time
        return false
    }
}
