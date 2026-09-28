package com.difft.android.base.migration

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IGlobalConfigsManager

/**
 * Decides whether the retiring app shows its migration entry (home title-bar icon, one-time notice,
 * guide page).
 *
 * The remote flag `migration.androidLegacyEntryEnabled` is the single switch: `true` opens, `false` closes.
 * Whether the new app happens to be installed plays no part — the guide page is also where the user
 * learns how to get it, so the entry must not vanish when the new app is removed.
 */
object LegacyMigrationGate {

    /**
     * What an absent flag means: closed. The key is what opens the entry, so a config that has not
     * shipped it never shows the entry before the new package is published on every channel.
     */
    const val DEFAULT_OPEN_WHEN_ABSENT = false

    fun isOpen(context: Context, configs: IGlobalConfigsManager): Boolean {
        if (!PeerApps.isLegacy(context)) return false
        val flag = configs.getNewGlobalConfigs()?.data?.migration?.androidLegacyEntryEnabled
        val open = flag ?: DEFAULT_OPEN_WHEN_ABSENT
        L.i { "[LegacyMigrationGate] open=$open remoteFlag=$flag" }
        return open
    }
}
