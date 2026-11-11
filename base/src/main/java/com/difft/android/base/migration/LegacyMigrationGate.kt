package com.difft.android.base.migration

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IGlobalConfigsManager









object LegacyMigrationGate {

    



    const val DEFAULT_OPEN_WHEN_ABSENT = false

    fun isOpen(context: Context, configs: IGlobalConfigsManager): Boolean {
        if (!PeerApps.isLegacy(context)) return false
        val flag = configs.getNewGlobalConfigs()?.data?.migration?.androidLegacyEntryEnabled
        val open = flag ?: DEFAULT_OPEN_WHEN_ABSENT
        L.i { "[LegacyMigrationGate] open=$open remoteFlag=$flag" }
        return open
    }
}
