package com.difft.android.base.utils.time

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateDataStoreEntryPoint
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.appScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs








object ServerTimeProvider {

    



    data class Anchor(val serverNow: Long, val anchorElapsed: Long)

    
    private const val OFFSET_PERSIST_THRESHOLD_MS = 1_000L

    
    private const val JUMP_WARN_THRESHOLD_MS = 24L * 60 * 60 * 1000

    




    private const val MIN_PLAUSIBLE_MS = 1_600_000_000_000L
    private const val MAX_PLAUSIBLE_MS = 4_102_444_800_000L

    
    private const val BACKWARD_TOLERANCE_MS = 30_000L

    
    private const val LAST_KNOWN_PERSIST_INTERVAL_MS = 30L * 60 * 1000

    @Volatile
    private var anchor: Anchor? = null

    
    @Volatile
    private var persistedOffset: Long = 0L

    
    @Volatile
    private var lastKnownServerTime: Long = 0L

    
    @Volatile
    private var lastPersistedLastKnown: Long = 0L

    
    @Volatile
    private var wallClock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }

    



    @Volatile
    private var persistenceEnabled: Boolean = true

    
    @Volatile
    private var dataStoreCache: DataStore<Preferences>? = null

    
    @Volatile
    private var initialLoadDone: Boolean = false

    
    @Volatile
    private var loadAttemptInFlight: Boolean = false

    



    private fun dataStore(): DataStore<Preferences>? {
        dataStoreCache?.let { return it }
        return runCatching {
            EntryPointAccessors.fromApplication(
                ApplicationHelper.instance,
                AppStateDataStoreEntryPoint::class.java,
            ).appStateDataStore()
        }.onFailure { L.w { "[ServerTime] dataStore resolve failed: ${it.message}" } }
            .getOrNull()
            ?.also { dataStoreCache = it }
    }

    init {
        
        loadInitial()
    }

    



    private fun loadInitial() {
        if (initialLoadDone || loadAttemptInFlight || !persistenceEnabled) return
        val ds = dataStore() ?: return 
        loadAttemptInFlight = true
        appScope.launch(Dispatchers.IO) {
            try {
                if (!persistenceEnabled) return@launch 
                runCatching {
                    val prefs = ds.data.first()
                    if (anchor == null) {
                        persistedOffset = prefs[AppStateKeys.SERVER_TIME_OFFSET] ?: AppStateDefaults.SERVER_TIME_OFFSET
                        val persistedLast = prefs[AppStateKeys.LAST_KNOWN_SERVER_TIME] ?: AppStateDefaults.LAST_KNOWN_SERVER_TIME
                        lastKnownServerTime = maxOf(lastKnownServerTime, persistedLast)
                        lastPersistedLastKnown = maxOf(lastPersistedLastKnown, persistedLast)
                    }
                    initialLoadDone = true
                }.onFailure { L.w { "[ServerTime] initial load failed: ${it.stackTraceToString()}" } }
            } finally {
                loadAttemptInFlight = false
            }
        }
    }

    
    fun nowMillis(): Long {
        anchor?.let { return it.serverNow + (elapsedClock() - it.anchorElapsed) }
        val fallback = wallClock() + persistedOffset
        return maxOf(fallback, lastKnownServerTime)
    }

    




    fun update(serverNow: Long, source: String) {
        if (serverNow <= 0L) return 

        
        if (serverNow < MIN_PLAUSIBLE_MS || serverNow > MAX_PLAUSIBLE_MS) {
            L.w { "[ServerTime] implausible serverNow rejected source=$source serverNow=$serverNow" }
            return
        }

        loadInitial() 

        val wasAnchored = anchor != null
        
        val previousNow = if (wasAnchored) nowMillis() else serverNow

        
        
        if (wasAnchored && serverNow < previousNow - BACKWARD_TOLERANCE_MS) {
            L.w { "[ServerTime] stale serverNow rejected source=$source serverNow=$serverNow currentNow=$previousNow" }
            return
        }

        anchor = Anchor(serverNow, elapsedClock())

        if (serverNow - previousNow > JUMP_WARN_THRESHOLD_MS) {
            L.w { "[ServerTime] large forward jump source=$source deltaMs=${serverNow - previousNow}" }
        }

        
        lastKnownServerTime = maxOf(lastKnownServerTime, serverNow)

        val newOffset = serverNow - wallClock()
        
        
        val offsetMoved = abs(newOffset - persistedOffset) > OFFSET_PERSIST_THRESHOLD_MS
        val lastKnownStale = lastKnownServerTime - lastPersistedLastKnown > LAST_KNOWN_PERSIST_INTERVAL_MS
        if (!wasAnchored || offsetMoved || lastKnownStale) {
            persistedOffset = newOffset
            lastPersistedLastKnown = lastKnownServerTime
            persist()
            L.i { "[ServerTime] anchored source=$source offsetMs=$newOffset" }
        }
    }

    fun isAnchored(): Boolean = anchor != null

    




    private fun persist() {
        if (!persistenceEnabled) return
        val ds = dataStore() ?: return
        appScope.launch(Dispatchers.IO) {
            runCatching {
                ds.edit {
                    it[AppStateKeys.SERVER_TIME_OFFSET] = persistedOffset
                    it[AppStateKeys.LAST_KNOWN_SERVER_TIME] = lastKnownServerTime
                }
            }.onFailure { L.w { "[ServerTime] persist failed: ${it.stackTraceToString()}" } }
        }
    }

    
    @VisibleForTesting
    fun resetForTest(
        wallClock: () -> Long,
        elapsedClock: () -> Long,
        persistedOffset: Long = 0L,
        lastKnownServerTime: Long = 0L,
    ) {
        this.persistenceEnabled = false
        this.wallClock = wallClock
        this.elapsedClock = elapsedClock
        this.anchor = null
        this.persistedOffset = persistedOffset
        this.lastKnownServerTime = lastKnownServerTime
        this.lastPersistedLastKnown = lastKnownServerTime
        this.initialLoadDone = true 
    }

    @VisibleForTesting
    fun persistedOffsetForTest(): Long = persistedOffset

    @VisibleForTesting
    fun lastKnownServerTimeForTest(): Long = lastKnownServerTime
}
