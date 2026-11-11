package com.difft.android.base.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.core.net.toUri
import android.os.Process
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.difft.android.base.BuildConfig
import com.difft.android.base.R
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.internal.managers.ViewComponentManager.FragmentContextWrapper
import kotlinx.coroutines.CoroutineName
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
    
    while (endIndex > 0 && (bytes[endIndex].toInt() and 0xC0) == 0x80) {
        endIndex--
    }

    return String(bytes, 0, endIndex, StandardCharsets.UTF_8)
}

fun Context.openExternalBrowser(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }.onFailure { e ->
        
        
        L.w { "[Base] openExternalBrowser failed url_host=${url.toUri().host} exception=${e.javaClass.simpleName}" }
        ToastUtil.show(R.string.base_link_open_failed)
    }
}

suspend inline fun <T, R> T.suspendLet(block: suspend (T) -> R): R {
    return block(this)
}

suspend inline fun <T> T.suspendAlso(block: suspend (T) -> Unit): T {
    block(this)
    return this
}




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





fun View.getLifecycleOwner(): LifecycleOwner? {
    findViewTreeLifecycleOwner()?.let { return it }
    
    (context as? LifecycleOwner)?.let { return it }
    
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is LifecycleOwner) return ctx
        ctx = ctx.baseContext
    }
    
    return null
}





val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("appScope") + dbKeyFailSoftExceptionHandler)

fun checkThread() {
    if (BuildConfig.DEBUG) {
        val isMainThread = Thread.currentThread() == Looper.getMainLooper().thread
        if (isMainThread) {
            val stackTrace = Throwable().stackTrace.take(10).joinToString("\n")
            L.d { "Running on main thread. Stack trace (limited to 10 frames):\n$stackTrace" }
        }
    }
}


fun String.sanitizeUrl(): String {
    return replace(Regex("""\?[^\s"')\]}>]+"""), "?[REDACTED]")
}

fun Context.restartApp(): Nothing {
    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    startActivity(intent)
    Process.killProcess(Process.myPid())
    exitProcess(0)
}