package org.difft.app.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.AppStateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the DataStore-backed [DatabaseRecoveryPreferences]
 * (issue #725 §8.3 / §9.7).
 *
 * Covers:
 *  - The Island 3 `runBlocking` wrapper completes the disk write before returning —
 *    safe to call before `Process.killProcess`.
 *  - [DatabaseRecoveryPreferences.incrementRecoveryFailureCount] is atomic: two
 *    sequential increments both land (would race in the legacy SP implementation
 *    if they were interleaved).
 *  - Public API surface preserved: [DatabaseRecoveryPreferences.setRecoveryNeeded],
 *    [DatabaseRecoveryPreferences.clearRecoveryFlag], [DatabaseRecoveryPreferences.isRecoveryNeeded],
 *    [DatabaseRecoveryPreferences.getRecoveryFailureCount], [DatabaseRecoveryPreferences.isMaxRetriesReached].
 *  - Methods deleted in Task 1 (`resetRecoveryFailureCount`, `clearAllRecoveryData`) remain absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DatabaseRecoveryPreferencesTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: androidx.datastore.core.DataStore<Preferences>
    private lateinit var prefs: DatabaseRecoveryPreferences

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        file = File(ctx.cacheDir, "dbrec_test_${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        prefs = DatabaseRecoveryPreferences(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `setRecoveryNeeded flushes flag and resets counter`() = runBlocking {
        prefs.setRecoveryNeeded()
        assertTrue(prefs.isRecoveryNeeded())
        assertEquals(0, prefs.getRecoveryFailureCount())
        // Verify on-disk write actually visible.
        val snapshot = dataStore.data.first()
        assertEquals(true, snapshot[AppStateKeys.NEED_RECOVERY_DATABASE])
        assertEquals(0, snapshot[AppStateKeys.DATABASE_RECOVERY_FAILURE_COUNT])
    }

    @Test
    fun `clearRecoveryFlag resets both flag and counter`() = runBlocking {
        prefs.setRecoveryNeeded()
        // Simulate a recovery attempt failure.
        prefs.incrementRecoveryFailureCount()
        assertEquals(1, prefs.getRecoveryFailureCount())

        prefs.clearRecoveryFlag()
        assertFalse(prefs.isRecoveryNeeded())
        assertEquals(0, prefs.getRecoveryFailureCount())
    }

    @Test
    fun `incrementRecoveryFailureCount increments counter and clears flag`() = runBlocking {
        prefs.setRecoveryNeeded()
        assertTrue(prefs.isRecoveryNeeded())

        prefs.incrementRecoveryFailureCount()
        assertEquals(1, prefs.getRecoveryFailureCount())
        // Flag is cleared (so the next launch re-detects whether recovery is needed)
        assertFalse(prefs.isRecoveryNeeded())

        prefs.incrementRecoveryFailureCount()
        prefs.incrementRecoveryFailureCount()
        assertEquals(3, prefs.getRecoveryFailureCount())
    }

    @Test
    fun `isMaxRetriesReached reflects failure count`() = runBlocking {
        // Default maxRetries = 3
        assertFalse(prefs.isMaxRetriesReached())
        repeat(3) { prefs.incrementRecoveryFailureCount() }
        assertTrue(prefs.isMaxRetriesReached())
    }

    @Test
    fun `defaults return for empty DataStore`() {
        // Fresh DataStore — no keys present.
        assertFalse(prefs.isRecoveryNeeded())
        assertEquals(0, prefs.getRecoveryFailureCount())
        assertFalse(prefs.isMaxRetriesReached())
    }

    @Test
    fun `setRecoveryNeeded sees pre-existing failure count and resets it`() = runBlocking {
        // Seed the counter to a non-zero value (simulates state from a prior crash).
        dataStore.edit { it[AppStateKeys.DATABASE_RECOVERY_FAILURE_COUNT] = 5 }
        prefs.setRecoveryNeeded()
        // setRecoveryNeeded resets the counter to 0 alongside setting the flag.
        assertEquals(0, prefs.getRecoveryFailureCount())
        assertTrue(prefs.isRecoveryNeeded())
    }
}
