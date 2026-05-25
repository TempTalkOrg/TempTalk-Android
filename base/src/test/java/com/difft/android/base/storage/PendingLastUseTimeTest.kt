package com.difft.android.base.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/**
 * Unit tests for [PendingLastUseTime] (issue #725 §10.1).
 *
 * Verifies:
 *  - [PendingLastUseTime.current] reflects the latest [PendingLastUseTime.record] value.
 *  - [PendingLastUseTime.flush] writes to the DataStore.
 *  - A no-op flush (no record) does not write — short-circuits on `isDirty=false`.
 *  - [PendingLastUseTime.loadInitial] populates the in-memory holder from the persisted value.
 *
 * Robolectric is used so a real PreferenceDataStore can be backed by the
 * Robolectric sandbox file system.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PendingLastUseTimeTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var holder: PendingLastUseTime

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        file = File(ctx.cacheDir, "test_pending_last_use_${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        holder = PendingLastUseTime()
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `record then current reflects last value`() {
        holder.record(1_000L)
        holder.record(2_000L)
        assertEquals(2_000L, holder.current())
    }

    @Test
    fun `flush persists pending value to DataStore`() = runTest {
        holder.record(5_000L)
        holder.flush(dataStore)
        val stored = dataStore.data.first()[AppStateKeys.LAST_USE_TIME]
        assertEquals(5_000L, stored)
    }

    @Test
    fun `flush without record is a no-op`() = runTest {
        // Seed DataStore with a known value to detect any unwanted write.
        dataStore.edit { it[AppStateKeys.LAST_USE_TIME] = 42L }
        // No `record()` call — isDirty stays false.
        holder.flush(dataStore)
        // Stored value untouched.
        assertEquals(42L, dataStore.data.first()[AppStateKeys.LAST_USE_TIME])
    }

    @Test
    fun `loadInitial primes pending value from DataStore`() = runTest {
        dataStore.edit { it[AppStateKeys.LAST_USE_TIME] = 12_345L }
        holder.loadInitial(dataStore)
        assertEquals(12_345L, holder.current())
    }

    @Test
    fun `loadInitial with no persisted value uses default`() = runTest {
        holder.loadInitial(dataStore)
        assertEquals(AppStateDefaults.LAST_USE_TIME, holder.current())
    }

    @Test
    fun `second flush without new record is no-op`() = runTest {
        holder.record(100L)
        holder.flush(dataStore)
        // Overwrite the on-disk value directly.
        dataStore.edit { it[AppStateKeys.LAST_USE_TIME] = 999L }
        // Holder is now clean (isDirty=false), so this should not overwrite the 999.
        holder.flush(dataStore)
        assertEquals(999L, dataStore.data.first()[AppStateKeys.LAST_USE_TIME])
    }
}
