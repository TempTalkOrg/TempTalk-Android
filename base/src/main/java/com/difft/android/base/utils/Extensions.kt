package com.difft.android.base.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.difft.android.base.BuildConfig
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.internal.managers.ViewComponentManager.FragmentContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

fun String.utf8Substring(maxUtf8Len: Int): String {
    val bytes = this.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= maxUtf8Len) {
        return this
    }

    var endIndex = maxUtf8Len
    // Ensure we do not cut a multi-byte character in the middle
    while (endIndex > 0 && (bytes[endIndex].toInt() and 0xC0) == 0x80) {
        endIndex--
    }

    // Convert the valid UTF-8 byte array back to a string
    return String(bytes, 0, endIndex, StandardCharsets.UTF_8)
}

fun Context.openExternalBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    startActivity(intent)
}

suspend inline fun <T, R> T.suspendLet(block: suspend (T) -> R): R {
    return block(this)
}

suspend inline fun <T> T.suspendAlso(block: suspend (T) -> Unit): T {
    block(this)
    return this
}

/**
 *  Assert that a value is true. this function only works in debug mode.
 */
fun assertInDebug(value: Boolean, lazyMessage: () -> String = { "" }) {
    if (BuildConfig.DEBUG) {
        assert(value, lazyMessage)
    }
}

const val DEFAULT_DEVICE_ID = 1

val application: ScopeApplication by lazy { ApplicationHelper.instance }

fun Context.globalHiltServices(): GlobalHiltEntryPoint {
    return EntryPointAccessors.fromApplication<GlobalHiltEntryPoint>(this)
}

val globalServices: GlobalHiltEntryPoint by lazy { application.globalHiltServices() }

fun Context.getSafeContext(): Context {
    fun isValidActivity(activity: Activity?) = activity != null && !activity.isFinishing && !activity.isDestroyed

    return when (this) {
        is FragmentContextWrapper -> {
            val baseContext = this.baseContext as? Activity
            if (baseContext != null && isValidActivity(baseContext)) {
                baseContext
            } else {
                this.applicationContext
            }
        }

        is Activity -> {
            if (isValidActivity(this)) {
                this
            } else {
                this.applicationContext
            }
        }

        else -> {
            this.applicationContext
        }
    }
}

/**
 * Get LifecycleOwner from view tree or context chain.
 * Handles ContextWrapper scenarios where direct cast fails.
 */
fun View.getLifecycleOwner(): LifecycleOwner? {
    // 1. Try findViewTreeLifecycleOwner first
    findViewTreeLifecycleOwner()?.let { return it }
    
    // 2. Try direct context cast
    (context as? LifecycleOwner)?.let { return it }
    
    // 3. Unwrap context to find Activity (handles ContextThemeWrapper etc.)
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is LifecycleOwner) return ctx
        ctx = ctx.baseContext
    }
    
    return null
}

val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun checkThread() {
    if (BuildConfig.DEBUG) {
        val isMainThread = Thread.currentThread() == Looper.getMainLooper().thread
        if (isMainThread) {
            val stackTrace = Throwable().stackTrace.take(10).joinToString("\n") // 限制堆栈深度为10
            L.d { "Running on main thread. Stack trace (limited to 10 frames):\n$stackTrace" }
        }
    }
}

fun Context.restartApp(): Nothing {
    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    startActivity(intent)
    Process.killProcess(Process.myPid())
    exitProcess(0)
}