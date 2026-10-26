package com.difft.android.base.notification

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #1350: the notification test store on a real file-backed DataStore, including the pinned key names (T14). */
class NotificationTestStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: NotificationTestStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("app_state.preferences_pb") })
        store = NotificationTestStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `nothing stored reads as no results`() = runBlocking {
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `each test is written under its own pinned key`() = runBlocking {
        val banner = NotificationTestResult(NotificationTestOutcome.OK, 5L, "aa")
        val call = NotificationTestResult(NotificationTestOutcome.FAIL, 6L, "bb")
        store.write(NotificationTestKind.BANNER, banner)
        store.write(NotificationTestKind.CALL, call)
        assertEquals(mapOf(NotificationTestKind.BANNER to banner, NotificationTestKind.CALL to call), store.read())
        val prefs = dataStore.data.first()
        assertEquals("ok|5|aa", prefs[stringPreferencesKey("notif_test_banner")])
        assertEquals("fail|6|bb", prefs[stringPreferencesKey("notif_test_call")])
    }

    // T14
    @Test
    fun `a corrupt value is skipped and the next write replaces it`() = runBlocking {
        val lock = NotificationTestResult(NotificationTestOutcome.OK, 7L, "cc")
        store.write(NotificationTestKind.LOCKSCREEN, lock)
        dataStore.edit { it[stringPreferencesKey("notif_test_banner")] = "ok|not-a-time" }
        assertEquals(mapOf(NotificationTestKind.LOCKSCREEN to lock), store.read())
        val banner = NotificationTestResult(NotificationTestOutcome.FAIL, 8L, "dd")
        store.write(NotificationTestKind.BANNER, banner)
        assertEquals(banner, store.read()[NotificationTestKind.BANNER])
    }
}
