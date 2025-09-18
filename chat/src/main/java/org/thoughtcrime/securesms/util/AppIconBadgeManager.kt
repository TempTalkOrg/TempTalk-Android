package org.thoughtcrime.securesms.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppIconBadgeManager @Inject constructor(
    @ApplicationContext
   val context: Context
) {
    fun updateAppIconBadgeNum(
        unreadMessageNumber: Int
    ) {
        setBadgeCount(unreadMessageNumber)
    }

    private fun setSamsungBadgeCount(context: Context, badgeCount: Int) {
        L.i { "setSamsungBadgeCount badgeCount=$badgeCount" }
        val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE")
        intent.putExtra("badge_count", badgeCount)
        intent.putExtra("badge_count_package_name", context.packageName)
        intent.putExtra("badge_count_class_name", getLauncherClassName(context))
        context.sendBroadcast(intent)
    }

    private fun getLauncherClassName(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.name
    }

    private fun setXiaomiBadgeCount(context: Context, badgeCount: Int) {
        L.i { "setXiaomiBadgeCount badgeCount=$badgeCount" }
        val intent = Intent("android.intent.action.APPLICATION_MESSAGE_UPDATE")
        intent.putExtra("android.intent.extra.update_application_component_name", "${context.packageName}/${getLauncherClassName(context)}")
        intent.putExtra("android.intent.extra.update_application_message_text", badgeCount.toString())
        context.sendBroadcast(intent)
    }

    private fun setHuaweiBadgeCount(context: Context, badgeCount: Int) {
        L.i { "setHuaweiBadgeCount badgeCount=$badgeCount" }
        val bundle = Bundle().apply {
            putString("package", context.packageName)
            putString("class", getLauncherClassName(context))
            putInt("badgenumber", badgeCount)
        }
        context.contentResolver.call(
            Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
            "change_badge",
            null,
            bundle
        )
    }

    private fun setOppoBadgeCount(context: Context, badgeCount: Int) {
        L.i { "setOppoBadgeCount badgeCount=$badgeCount" }
        val intent = Intent("com.oppo.unsettledevent")
        intent.putExtra("pakeageName", context.packageName)
        intent.putExtra("number", badgeCount)
        intent.putExtra("upgradeNumber", badgeCount)
        context.sendBroadcast(intent)
    }

    private fun setBadgeCount(badgeCount: Int) {
        when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> setSamsungBadgeCount(context, badgeCount)
            "xiaomi" -> setXiaomiBadgeCount(context, badgeCount)
            "huawei" -> setHuaweiBadgeCount(context, badgeCount)
            "oppo" -> setOppoBadgeCount(context, badgeCount)
            else -> {
                //don't support show badge count in other brands device
            }
        }
    }
}