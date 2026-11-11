package com.difft.android.base.monitor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.user.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton














@Singleton
class ProcessExitProbe @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>,
    private val userManager: UserManager,
    private val healthReporter: KeepAliveHealthReporter
) {

    





    suspend fun probe() {
        var keepAliveEnabled: Boolean? = null
        var newExits: List<ExitSample> = emptyList()
        runCatching {
            
            
            
            
            userManager.getUserData()?.let {
                keepAliveEnabled = it.keepAliveEnabled
            }
        }.onFailure {
            L.w { "[ProcessExit] probe failed: ${it.stackTraceToString()}" }
        }

        
        
        
        
        val enabled = keepAliveEnabled
        val collectSamples = enabled == null ||
            runCatching { healthReporter.isReporting(enabled) }.getOrDefault(enabled)
        runCatching {
            newExits = scanExits(collectSamples)
        }.onFailure {
            L.w { "[ProcessExit] exit scan failed: ${it.stackTraceToString()}" }
        }

        
        
        healthReporter.onProbe(keepAliveEnabled, newExits)
    }

    



    suspend fun reportHealthIfDue() {
        runCatching { healthReporter.onProbe(userManager.getUserData()?.keepAliveEnabled, emptyList()) }
            .onFailure { L.w { "[KeepAliveHealth] foreground check failed: ${it.stackTraceToString()}" } }
    }

    



    private suspend fun scanExits(collectSamples: Boolean): List<ExitSample> {
        val samples = ArrayList<ExitSample>()
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return samples

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return samples
        
        
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        if (exits.isEmpty()) return samples

        val storedMarker: Long? = appStateDataStore.data.first()[AppStateKeys.LAST_SEEN_EXIT_TS]

        if (storedMarker == null) {
            
            
            
            val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
            runCatching {
                appStateDataStore.edit { it[AppStateKeys.LAST_SEEN_EXIT_TS] = newest }
            }.onFailure { L.w { "[ProcessExit] dedup marker seed failed: ${it.stackTraceToString()}" } }
            return samples
        }

        
        
        val marker: Long = storedMarker
        var newestSeen = marker
        exits.forEach { info ->
            if (info.timestamp <= marker) return@forEach
            if (info.timestamp > newestSeen) newestSeen = info.timestamp
            if (collectSamples) samples.add(KeepAliveHealthModel.toSample(info.reason, info.importance))
            val isFreezeLike = info.reason == ApplicationExitInfo.REASON_FREEZER ||
                info.reason == ApplicationExitInfo.REASON_ANR ||
                info.reason == ApplicationExitInfo.REASON_LOW_MEMORY
            if (!isFreezeLike) return@forEach

            L.w {
                "[ProcessExit] reason=${exitReasonName(info.reason)} ts=${info.timestamp} " +
                        "desc=${info.description} importance=${info.importance}"
            }
        }

        
        
        
        if (newestSeen > marker) {
            runCatching {
                appStateDataStore.edit { it[AppStateKeys.LAST_SEEN_EXIT_TS] = newestSeen }
            }.onFailure { L.w { "[ProcessExit] dedup marker persist failed: ${it.stackTraceToString()}" } }
        }
        return samples
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        else -> reason.toString()
    }
}
