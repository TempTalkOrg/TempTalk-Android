package com.difft.android.messageserialization.db.store

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import org.difft.app.database.DatabaseRecoveryPreferences
import org.difft.app.database.WCDB

/**
 * Helper for tests that need an in-memory WCDB instance.
 *
 * Robolectric runs each test in an isolated sandbox, so the database file
 * created under `context.cacheDir`/databases is automatically cleaned up
 * between tests. No explicit teardown required.
 */
object TestWcdbFactory {

    /**
     * Builds a fully-initialized [WCDB] instance rooted at the Robolectric
     * sandbox. Callers typically invoke from `@Before setUp()`.
     *
     * The `publicKeyInfo` table (and other tables) are created lazily on
     * first access; the test can simply call `wcdb.publicKeyInfo.*`.
     */
    fun createInMemoryWcdb(context: Context): WCDB {
        // Issue #725 Task 5 made DatabaseRecoveryPreferences DataStore-backed. Construct
        // a sandboxed preferences DataStore for tests — Robolectric cleans the file dir
        // automatically between tests.
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile("test_app_state") },
        )
        return WCDB(
            context,
            TestScope(),
            DatabaseRecoveryPreferences(dataStore),
        )
    }
}
