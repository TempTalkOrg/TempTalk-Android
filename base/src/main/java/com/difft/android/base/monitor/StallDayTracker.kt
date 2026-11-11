package com.difft.android.base.monitor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton


object StallRule {
    




    const val STALL_AFTER_MS = 2 * 60 * 60_000L
}







@Singleton
class StallDayTracker @Inject constructor(
    @param:AppStateDataStore private val dataStore: DataStore<Preferences>,
) {

    internal var wallClock: () -> Long = { System.currentTimeMillis() }
    internal var zoneOffsetMs: (Long) -> Long = { TimeZone.getDefault().getOffset(it).toLong() }
    internal var launch: (suspend () -> Unit) -> Unit = { block -> appScope.launch { block() } }

    
    
    private val recentStallDays: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var switchedOnDay: Long? = null

    private fun localWall(): Long = wallClock().let { it + zoneOffsetMs(it) }

    private fun today(): Long = Math.floorDiv(localWall(), DAY_MS)

    
    fun onStall() {
        
        
        val day = today()
        recentStallDays += day
        launch {
            runCatching {
                dataStore.edit { prefs ->
                    val pruneAt = maxOf(localWall(), day * DAY_MS)
                    val kept = LateDeliveryTracker.window(prefs[AppStateKeys.BG_STALL_DAYS], pruneAt)
                    val inWindow = day >= Math.floorDiv(pruneAt, DAY_MS) - (LateDeliveryRule.WINDOW_DAYS - 1)
                    val next = if (inWindow) kept + (day to (kept[day] ?: 0) + 1) else kept
                    prefs[AppStateKeys.BG_STALL_DAYS] = LateDeliveryTracker.encode(next)
                }
            }.onFailure { L.w { "[StallDay] write failed error=${it.javaClass.simpleName}" } }
        }
    }

    
    suspend fun stallDayKeys(): Set<Long> {
        val today = today()
        val stored = LateDeliveryTracker.window(dataStore.data.first()[AppStateKeys.BG_STALL_DAYS], localWall()).filterValues { it > 0 }.keys
        return stored + recentStallDays.filter { it in today - (LateDeliveryRule.WINDOW_DAYS - 1)..today }
    }

    




    fun onKeepAliveEnabled() {
        val today = today()
        switchedOnDay = today
        launch {
            runCatching {
                dataStore.edit { it[AppStateKeys.BG_KEEP_ALIVE_ON_DAY] = today }
            }.onFailure { L.w { "[StallDay] keep-alive day write failed error=${it.javaClass.simpleName}" } }
        }
    }

    




    suspend fun keepAliveOnDay(): Long {
        val day = switchedOnDay ?: dataStore.data.first()[AppStateKeys.BG_KEEP_ALIVE_ON_DAY]
        val today = today()
        if (day != null && day <= today) return day
        switchedOnDay = today
        runCatching {
            dataStore.edit { prefs ->
                val stored = prefs[AppStateKeys.BG_KEEP_ALIVE_ON_DAY]
                if (stored == null || stored > today) prefs[AppStateKeys.BG_KEEP_ALIVE_ON_DAY] = today
            }
        }.onFailure { L.w { "[StallDay] keep-alive day write failed error=${it.javaClass.simpleName}" } }
        return today
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
