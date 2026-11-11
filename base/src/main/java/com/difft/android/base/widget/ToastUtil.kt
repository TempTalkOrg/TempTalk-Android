package com.difft.android.base.widget

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import com.difft.android.base.utils.application






object ToastUtil {

    private val mainHandler = Handler(Looper.getMainLooper())

    



    fun show(message: String) {
        runOnMainThread {
            Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
        }
    }

    



    fun show(@StringRes messageRes: Int) {
        runOnMainThread {
            Toast.makeText(application, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    



    fun showLong(message: String) {
        runOnMainThread {
            Toast.makeText(application, message, Toast.LENGTH_LONG).show()
        }
    }

    



    fun showLong(@StringRes messageRes: Int) {
        runOnMainThread {
            Toast.makeText(application, messageRes, Toast.LENGTH_LONG).show()
        }
    }

    private inline fun runOnMainThread(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }
}
