package com.difft.android.base.notification

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    private fun ticket(runId: String = java.util.UUID.randomUUID().toString()) =
        NotificationTestCallTicket(runId, 1_000L, "original-fingerprint", 61_000L)

    @Test
    fun `timeout and late objective shown atomically preserve the original ticket fingerprint`() = runBlocking {
        val ticket = ticket()
        store.prepareCall(ticket)
        assertEquals(NotificationTestCallUpdate.RECORDED, store.updateCall(ticket.runId, 16_000L, shown = false))
        assertEquals(NotificationTestOutcome.FAIL, store.read()[NotificationTestKind.CALL]?.outcome)
        assertEquals(NotificationTestCallUpdate.RECORDED, store.updateCall(ticket.runId, 17_000L, shown = true))
        assertEquals(NotificationTestResult(NotificationTestOutcome.OK, 17_000L, ticket.fingerprint), store.read()[NotificationTestKind.CALL])
        assertEquals(NotificationTestCallUpdate.ALREADY_SHOWN, store.updateCall(ticket.runId, 18_000L, shown = false))
        assertEquals(NotificationTestOutcome.OK, store.read()[NotificationTestKind.CALL]?.outcome)
    }

    @Test
    fun `a new ticket supersedes an old display cancellation and timeout`() = runBlocking {
        val old = ticket()
        val next = ticket()
        store.prepareCall(old)
        store.prepareCall(next)
        store.cancelCall(old.runId)
        assertEquals(NotificationTestCallUpdate.REJECTED, store.updateCall(old.runId, 2_000L, shown = true))
        assertEquals(NotificationTestCallUpdate.REJECTED, store.updateCall(old.runId, 2_000L, shown = false))
        assertEquals(next, store.readCall(2_000L))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `canceled expired missing and corrupt tickets cannot record success`() = runBlocking {
        val ticket = ticket()
        assertEquals(NotificationTestCallUpdate.REJECTED, store.updateCall(ticket.runId, 2_000L, shown = true))
        store.prepareCall(ticket)
        store.cancelCall(ticket.runId)
        assertEquals(NotificationTestCallUpdate.REJECTED, store.updateCall(ticket.runId, 2_000L, shown = true))
        store.prepareCall(ticket)
        assertEquals(NotificationTestCallUpdate.REJECTED, store.updateCall(ticket.runId, 61_001L, shown = true))
        assertEquals(null, store.readCall(61_001L))
        dataStore.edit { it[com.difft.android.base.storage.AppStateKeys.NOTIF_TEST_CALL_TICKET] = "1|bad" }
        assertEquals(NotificationTestCallUpdate.REJECTED, store.updateCall(ticket.runId, 2_000L, shown = true))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `cold store instance records actual shown under the stored fingerprint`() = runBlocking {
        val ticket = ticket()
        store.prepareCall(ticket)
        val coldStore = NotificationTestStore(dataStore)
        assertEquals(NotificationTestCallUpdate.RECORDED, coldStore.updateCall(ticket.runId, 2_000L, shown = true))
        assertEquals(NotificationTestResult(NotificationTestOutcome.OK, 2_000L, ticket.fingerprint), coldStore.read()[NotificationTestKind.CALL])
    }

    @Test
    fun `concurrent timeout and display never leave a failure after display`() = runBlocking {
        val ticket = ticket()
        store.prepareCall(ticket)
        kotlinx.coroutines.coroutineScope {
            val timeout = async { store.updateCall(ticket.runId, 2_000L, shown = false) }
            val shown = async { store.updateCall(ticket.runId, 2_001L, shown = true) }
            timeout.await()
            shown.await()
        }
        assertEquals(NotificationTestOutcome.OK, store.read()[NotificationTestKind.CALL]?.outcome)
    }

    @Test
    fun `seen categories accumulate under their pinned key and survive a cold instance`() = runBlocking {
        assertTrue(store.readSeenCategories().isEmpty())
        store.markCategorySeen(NotificationChannelIds.CALL)
        store.markCategorySeen(NotificationChannelIds.CRITICAL_ALERT)
        store.markCategorySeen(NotificationChannelIds.CALL)
        val expected = setOf(NotificationChannelIds.CALL, NotificationChannelIds.CRITICAL_ALERT)
        assertEquals(expected, NotificationTestStore(dataStore).readSeenCategories())
        assertEquals(expected, dataStore.data.first()[stringSetPreferencesKey("notif_category_seen")])
    }
}
