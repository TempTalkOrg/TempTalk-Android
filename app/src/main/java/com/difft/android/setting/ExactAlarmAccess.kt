package com.difft.android.setting

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.difft.android.base.log.lumberjack.L

enum class ExactAlarmState { NotApplicable, Granted, NotGranted }

/**
 * Exact-alarm access for the background-connection settings row. Whether the row applies is read
 * from this build's merged manifest, so a channel that strips SCHEDULE_EXACT_ALARM (Google Play)
 * hides it without any channel-specific code.
 */
object ExactAlarmAccess {

    /** NotApplicable below API 31 or when this build does not declare SCHEDULE_EXACT_ALARM. */
    fun state(context: Context): ExactAlarmState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isDeclared(context)) {
            return ExactAlarmState.NotApplicable
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return if (alarmManager?.canScheduleExactAlarms() == true) {
            ExactAlarmState.Granted
        } else {
            ExactAlarmState.NotGranted
        }
    }

    fun openSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            L.e(e) { "Failed to open exact alarm settings" }
        }
    }

    private fun isDeclared(context: Context): Boolean = try {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions?.contains(Manifest.permission.SCHEDULE_EXACT_ALARM) == true
    } catch (e: Exception) {
        L.w { "[ExactAlarm] getPackageInfo failed: ${e.stackTraceToString()}" }
        false
    }
}
