package com.difft.android.base.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory holder for `lastUseTime` ticks that eliminates per-tick DataStore writes.
 *
 * [record] updates an [AtomicLong] on each 10 s tick (non-blocking). [flush] commits the
 * value to DataStore at most once per fg→bg transition. [current] provides the same
 * accuracy as the old `userData.lastUseTime` read.
 *
 * **isDirty ordering**: `isDirty = false` is set BEFORE the DataStore write so a concurrent
 * [record] during the in-flight write sets `isDirty = true` and the next flush picks it up.
 * On write failure, `isDirty` is restored to `true` for retry.
 */
@Singleton
class PendingLastUseTime @Inject constructor() {

    private val pendingValue = AtomicLong(AppStateDefaults.LAST_USE_TIME)

    @Volatile
    private var isDirty: Boolean = false

    /** Updates the pending value and marks dirty. The actual disk write happens in [flush]. */
    fun record(timestamp: Long) {
        pendingValue.set(timestamp)
        isDirty = true
    }

    /** Current pending timestamp, read by `shouldShowScreenLock`. */
    fun current(): Long = pendingValue.get()

    /**
     * Load the persisted `lastUseTime` from DataStore on app start so the in-memory
     * holder matches what's on disk. Called once during the startup chain.
     */
    suspend fun loadInitial(dataStore: DataStore<Preferences>) {
        val persisted = dataStore.data.first()[AppStateKeys.LAST_USE_TIME] ?: AppStateDefaults.LAST_USE_TIME
        pendingValue.set(persisted)
        isDirty = false
    }

    /** Persists the pending value to DataStore if dirty. No-op if nothing changed since last flush. */
    suspend fun flush(dataStore: DataStore<Preferences>) {
        if (!isDirty) return
        val ts = pendingValue.get()
        isDirty = false  // Reset before the write — see class kdoc for ordering rationale.
        try {
            dataStore.edit { it[AppStateKeys.LAST_USE_TIME] = ts }
            L.i { "[Storage][app_state] flushed lastUseTime=$ts" }
        } catch (e: Exception) {
            isDirty = true  // Restore so the next fg→bg transition retries.
            L.w { "[Storage][app_state] flush lastUseTime failed; isDirty restored: ${e.stackTraceToString()}" }
        }
    }
}
