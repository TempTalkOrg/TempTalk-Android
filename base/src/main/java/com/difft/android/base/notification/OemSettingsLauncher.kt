package com.difft.android.base.notification

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L










object OemSettingsLauncher {

    


    fun openFirstAvailable(context: Context, intents: List<Intent>, tag: String): Boolean {
        val verified = intents.firstOrNull { intent ->
            runCatching { context.packageManager.queryIntentActivities(intent, 0).isNotEmpty() }.getOrDefault(false)
        }
        val ordered = (listOfNotNull(verified) + intents.filter { it !== verified } + appDetailsIntent(context))
            .onEach { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return openInOrder(context, ordered, tag)
    }

    
    fun openInOrder(context: Context, intents: List<Intent>, tag: String, fix: String = tag): Boolean =
        startFirst(intents, tag, fix) { intent ->
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

    
    internal fun startFirst(intents: List<Intent>, tag: String, fix: String = tag, start: (Intent) -> Unit): Boolean {
        for ((index, intent) in intents.withIndex()) {
            val target = intent.component?.className ?: intent.action
            try {
                start(intent)
                OemJumpLog.log(fix, opened = index + 1, count = intents.size, target = shortTarget(intent))
                return true
            } catch (e: SecurityException) {
                L.w { "[$tag] jump failed SecurityException target=$target" }
            } catch (e: ActivityNotFoundException) {
                L.w { "[$tag] jump failed ActivityNotFound target=$target" }
            } catch (e: Exception) {
                L.w { "[$tag] jump failed target=$target error=${e.stackTraceToString()}" }
            }
        }
        OemJumpLog.log(fix, opened = 0, count = intents.size, target = "none")
        return false
    }

    private fun shortTarget(intent: Intent): String = when {
        intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> "appDetails"
        intent.component != null -> intent.component!!.className.substringAfterLast('.')
        else -> intent.action?.substringAfterLast('.') ?: "-"
    }

    




    fun canResolveAny(context: Context, intents: List<Intent>): Boolean =
        intents.any { intent ->
            runCatching {
                context.packageManager.queryIntentActivities(intent, 0).any { info ->
                    val activity = info.activityInfo
                    activity != null && isStartable(activity.exported, activity.permission) { permission ->
                        context.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
                    }
                }
            }.getOrDefault(false)
        }

    
    internal fun isStartable(exported: Boolean, permission: String?, granted: (String) -> Boolean): Boolean =
        exported && (permission.isNullOrEmpty() || granted(permission))

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            data = "package:${context.packageName}".toUri()
        }
}
