package com.difft.android.setting

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess








fun interface AppExiter {
    fun exit(relaunch: Boolean): Nothing
}

@Singleton
class AppExiterImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppExiter {
    







    override fun exit(relaunch: Boolean): Nothing {
        if (relaunch) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(this)
                } catch (e: Exception) {
                    L.e { "[Logout] exit startActivity failed: ${e.stackTraceToString()}" }
                }
            }
        } else {
            
            
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.appTasks.forEach { it.finishAndRemoveTask() }
            }.onFailure { L.w { "[Logout] finishAndRemoveTask failed: ${it.message}" } }
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
