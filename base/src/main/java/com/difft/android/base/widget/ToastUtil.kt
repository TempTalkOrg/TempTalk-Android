package com.difft.android.base.widget

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import com.difft.android.base.utils.application

/**
 * Toast utility class.
 * Uses Application context to avoid issues when activity is destroyed.
 * Thread-safe: can be called from any thread, automatically switches to main thread.
 */
object ToastUtil {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Show a short toast.
     * @param message the message to display
     */
    fun show(message: String) {
        runOnMainThread {
            Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show a short toast.
     * @param messageRes the string resource ID to display
     */
    fun show(@StringRes messageRes: Int) {
        runOnMainThread {
            Toast.makeText(application, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show a long toast.
     * @param message the message to display
     */
    fun showLong(message: String) {
        runOnMainThread {
            Toast.makeText(application, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Show a long toast.
     * @param messageRes the string resource ID to display
     */
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
