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

/**
 * Terminal exit of the logout sequence. `relaunch = false` means "no host on screen".
 *
 * A seam rather than an inline call so the logout sequence is reachable from a unit test:
 * `Process.killProcess` would otherwise take down the Gradle test worker. Same idea as
 * `RecoveryFlowCoordinator.Host.restartApp()`, which is faked the same way in its own test.
 */
fun interface AppExiter {
    fun exit(relaunch: Boolean): Nothing
}

@Singleton
class AppExiterImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppExiter {
    /**
     * relaunch = true  -> launcher intent with CLEAR_TASK|NEW_TASK (wipes the stale task), then kill.
     *   FLAG_ACTIVITY_CLEAR_TASK requires FLAG_ACTIVITY_NEW_TASK on API >= 24 from a non-Activity
     *   context — without it startActivity silently fails.
     * relaunch = false -> startActivity is blocked from the background on API 29+, so the relaunch
     *   is skipped and the task is finished explicitly; otherwise tapping the launcher icon later
     *   would restore the killed process on top of a stale, credential-less Activity.
     */
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
            // getAppTasks()/finishAndRemoveTask() only touch this app's own tasks and need no
            // permission. runCatching so an OEM quirk can never block the exit.
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.appTasks.forEach { it.finishAndRemoveTask() }
            }.onFailure { L.w { "[Logout] finishAndRemoveTask failed: ${it.message}" } }
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
