package com.difft.android.base.utils

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L




















object GmsHealth {
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val GSERVICES_AUTHORITY = "com.google.android.gsf.gservices"

    @Volatile
    private var broken: Boolean? = null

    
    fun isGmsBroken(context: Context): Boolean =
        broken ?: detect(context.packageManager).also { broken = it }

    private fun detect(pm: PackageManager): Boolean {
        
        val gmsInstalled = runCatching { pm.getPackageInfo(GMS_PACKAGE, 0) }.isSuccess
        if (!gmsInstalled) return false
        val gservicesMissing = runCatching { pm.resolveContentProvider(GSERVICES_AUTHORITY, 0) == null }
            .getOrDefault(false)
        if (gservicesMissing) {
            L.w { "[GmsHealth] GMS installed but $GSERVICES_AUTHORITY is unresolvable; skipping optional GMS IPC" }
        }
        return gservicesMissing
    }

    @VisibleForTesting
    fun resetForTest() {
        broken = null
    }
}
