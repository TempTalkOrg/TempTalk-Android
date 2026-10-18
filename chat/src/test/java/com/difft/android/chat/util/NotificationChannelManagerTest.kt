package com.difft.android.chat.util

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.storage.AppStateKeys
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #1350 channel maintenance: in-place rename keeps the user's settings (T10), the legacy
 * delete-and-recreate runs once per version (T11), and the critical-alert bypass reconcile covers
 * already-authorised users and reports what the system actually kept (T12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var nm: NotificationManager
    private lateinit var context: Context
    private val channels = mutableMapOf<String, NotificationChannel>()
    private var policyAccess = false

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("p.preferences_pb") })
        nm = mockk(relaxed = true)
        every { nm.getNotificationChannel(any()) } answers { channels[firstArg()] }
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers {
            val channel = firstArg<NotificationChannel>()
            channels.putIfAbsent(channel.id, channel)
        }
        every { nm.deleteNotificationChannel(any()) } answers { channels.remove(firstArg<String>()) }
        every { nm.isNotificationPolicyAccessGranted } answers { policyAccess }
        every { nm.notificationChannelGroups } returns emptyList()
        context = spyk(ApplicationProvider.getApplicationContext<Context>())
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun manager() = NotificationChannelManager(context, store)

    private fun channel(id: String, name: String, importance: Int, configure: NotificationChannel.() -> Unit = {}) =
        NotificationChannel(id, name, importance).apply(configure).also { channels[id] = it }

    // T10
    @Test
    fun `an existing channel is renamed in place without lowering or resetting anything`() {
        channel(NotificationChannelIds.MESSAGE, "MESSAGE", NotificationManager.IMPORTANCE_LOW)
        channel(NotificationChannelIds.CRITICAL_ALERT, "CRITICAL_ALERT_V2", NotificationManager.IMPORTANCE_HIGH) {
            setBypassDnd(true)
        }
        // Everything else exists under its localized name already, so only the two above change.
        listOf(
            NotificationChannelIds.MESSAGE_SUMMARY, NotificationChannelIds.CALL,
            NotificationChannelIds.ONGOING_CALL, NotificationChannelIds.BACKGROUND,
        ).forEach { id ->
            channel(id, context.getString(NotificationChannelIds.nameRes(id)!!), NotificationManager.IMPORTANCE_DEFAULT)
        }
        val submitted = mutableListOf<NotificationChannel>()
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { submitted += firstArg<NotificationChannel>() }

        manager().ensureChannels()

        assertEquals(setOf("MESSAGE", "CRITICAL_ALERT_V2"), submitted.map { it.id }.toSet())
        val message = submitted.single { it.id == "MESSAGE" }
        assertEquals("Messages", message.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, message.importance)
        val critical = submitted.single { it.id == "CRITICAL_ALERT_V2" }
        assertEquals("Critical alerts", critical.name.toString())
        assertTrue(critical.canBypassDnd())
        verify(exactly = 0) { nm.deleteNotificationChannel(any()) }
    }

    @Test
    fun `a legacy message channel without a group gets the group on rename and others keep theirs`() {
        channel(NotificationChannelIds.MESSAGE, "MESSAGE", NotificationManager.IMPORTANCE_HIGH)
        channel(NotificationChannelIds.MESSAGE_SUMMARY, "MESSAGE_SUMMARY", NotificationManager.IMPORTANCE_DEFAULT) {
            group = "SOME_OTHER_GROUP"
        }
        channel(NotificationChannelIds.CALL, "CALL", NotificationManager.IMPORTANCE_HIGH)
        val submitted = mutableListOf<NotificationChannel>()
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { submitted += firstArg<NotificationChannel>() }

        manager().ensureChannels()

        assertEquals(NotificationChannelIds.MESSAGE_GROUP, submitted.single { it.id == "MESSAGE" }.group)
        assertEquals("SOME_OTHER_GROUP", submitted.single { it.id == "MESSAGE_SUMMARY" }.group)
        assertNull(submitted.single { it.id == "CALL" }.group)
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh")
    fun `the localized name follows the app language`() {
        channel(NotificationChannelIds.CALL, "CALL", NotificationManager.IMPORTANCE_HIGH)
        val submitted = mutableListOf<NotificationChannel>()
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { submitted += firstArg<NotificationChannel>() }

        manager().ensureChannels()

        assertEquals("来电", submitted.single { it.id == "CALL" }.name.toString())
    }

    @Test
    fun `a channel already carrying its localized name is not touched`() {
        listOf(
            NotificationChannelIds.MESSAGE_SUMMARY, NotificationChannelIds.MESSAGE, NotificationChannelIds.CALL,
            NotificationChannelIds.ONGOING_CALL, NotificationChannelIds.BACKGROUND, NotificationChannelIds.CRITICAL_ALERT,
        ).forEach { id ->
            channel(id, context.getString(NotificationChannelIds.nameRes(id)!!), NotificationManager.IMPORTANCE_HIGH)
        }
        every { nm.notificationChannelGroups } returns listOf(
            NotificationChannelGroup(NotificationChannelIds.MESSAGE_GROUP, context.getString(NotificationChannelIds.nameRes(NotificationChannelIds.MESSAGE_GROUP)!!))
        )

        manager().ensureChannels()

        verify(exactly = 0) { nm.createNotificationChannel(any<NotificationChannel>()) }
        verify(exactly = 0) { nm.createNotificationChannelGroup(any<NotificationChannelGroup>()) }
    }

    @Test
    fun `missing channels are created with their localized names`() {
        manager().ensureChannels()

        assertEquals(
            setOf("MESSAGE_SUMMARY", "MESSAGE", "CALL", "ONGOING_CALL", "BACKGROUND", "CRITICAL_ALERT_V2"),
            channels.keys,
        )
        assertEquals("Calls", channels.getValue("CALL").name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channels.getValue("CALL").importance)
        assertTrue(channels.getValue("CRITICAL_ALERT_V2").canBypassDnd())
    }

    // T11 / R1-F2
    @Test
    fun `the migration deletes only the legacy critical-alert id and only once`() = runBlocking {
        channel(NotificationChannelIds.LEGACY_CRITICAL_ALERT, "CRITICAL_ALERT", NotificationManager.IMPORTANCE_HIGH)
        // Channels the app posts to, including legacy-looking ones with badges on, must survive.
        listOf(
            NotificationChannelIds.MESSAGE, NotificationChannelIds.CALL, NotificationChannelIds.ONGOING_CALL,
            NotificationChannelIds.BACKGROUND, NotificationChannelIds.CRITICAL_ALERT,
        ).forEach { id -> channel(id, id, NotificationManager.IMPORTANCE_HIGH) { setShowBadge(true) } }
        val manager = manager()

        manager.ensureChannels()
        verify(exactly = 0) { nm.deleteNotificationChannel(any()) }

        manager.migrateIfNeeded()
        verify(exactly = 1) { nm.deleteNotificationChannel("CRITICAL_ALERT") }
        verify(exactly = 1) { nm.deleteNotificationChannel(any()) }
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, store.data.first()[AppStateKeys.NOTIFICATION_CHANNEL_VERSION])

        channel(NotificationChannelIds.LEGACY_CRITICAL_ALERT, "CRITICAL_ALERT", NotificationManager.IMPORTANCE_HIGH)
        manager.migrateIfNeeded()
        verify(exactly = 1) { nm.deleteNotificationChannel(any()) }
    }

    @Test
    fun `the migration without a legacy channel deletes nothing but still stamps the version`() = runBlocking {
        channel(NotificationChannelIds.CALL, "Calls", NotificationManager.IMPORTANCE_HIGH) { setShowBadge(true) }
        manager().migrateIfNeeded()

        verify(exactly = 0) { nm.deleteNotificationChannel(any()) }
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, store.data.first()[AppStateKeys.NOTIFICATION_CHANNEL_VERSION])
    }

    @Test
    fun `the migration lowers an audible silent-spec channel once and never raises or touches others`() = runBlocking {
        channel(NotificationChannelIds.ONGOING_CALL, "ONGOING_CALL", NotificationManager.IMPORTANCE_DEFAULT) { setShowBadge(true) }
        channel(NotificationChannelIds.BACKGROUND, "Background connection", NotificationManager.IMPORTANCE_MIN)
        channel(NotificationChannelIds.CALL, "Calls", NotificationManager.IMPORTANCE_HIGH)
        val submitted = mutableListOf<NotificationChannel>()
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { submitted += firstArg<NotificationChannel>() }
        val manager = manager()

        manager.migrateIfNeeded()

        val ongoing = submitted.single()
        assertEquals("ONGOING_CALL", ongoing.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ongoing.importance)
        assertEquals("Ongoing call", ongoing.name.toString())
        verify(exactly = 0) { nm.deleteNotificationChannel(any()) }

        manager.migrateIfNeeded()
        assertEquals(1, submitted.size)
    }

    // R1-F1
    @Test
    @Config(sdk = [26])
    fun `a fresh API 26 install creates the group before the channels without the API 28 lookup`() {
        every { nm.notificationChannelGroups } returns emptyList()
        val groups = mutableListOf<NotificationChannelGroup>()
        every { nm.createNotificationChannelGroup(any<NotificationChannelGroup>()) } answers { groups += firstArg<NotificationChannelGroup>() }

        manager().ensureChannels()

        assertEquals(listOf(NotificationChannelIds.MESSAGE_GROUP), groups.map { it.id })
        assertTrue(channels.keys.containsAll(setOf("MESSAGE", "MESSAGE_SUMMARY")))
    }

    // T12
    @Test
    fun `an already-authorised user is reconciled on the first foreground and the result is read back`() = runBlocking {
        policyAccess = true
        channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts", NotificationManager.IMPORTANCE_HIGH)
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers {
            // The system keeps a category the user has touched: the re-create changes nothing.
        }

        val result = manager().reconcileCriticalAlertBypass(appVersion = "2.4.6")

        assertEquals(CriticalAlertBypass.NEEDS_USER_ACTION, result)
        verify(exactly = 1) { nm.createNotificationChannel(match<NotificationChannel> { it.canBypassDnd() }) }
        assertEquals("2.4.6", store.data.first()[AppStateKeys.CRITICAL_ALERT_BYPASS_ATTEMPT_VERSION])
    }

    @Test
    fun `a successful re-create reports enabled`() = runBlocking {
        policyAccess = true
        val old = channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts", NotificationManager.IMPORTANCE_HIGH)
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { old.setBypassDnd(true) }

        assertEquals(CriticalAlertBypass.ENABLED, manager().reconcileCriticalAlertBypass(appVersion = "2.4.6"))
    }

    @Test
    fun `the re-create is attempted at most once per app version`() = runBlocking {
        policyAccess = true
        channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts", NotificationManager.IMPORTANCE_HIGH)
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { }
        val manager = manager()

        manager.reconcileCriticalAlertBypass(appVersion = "2.4.6")
        assertEquals(CriticalAlertBypass.NEEDS_USER_ACTION, manager.reconcileCriticalAlertBypass(appVersion = "2.4.6"))
        verify(exactly = 1) { nm.createNotificationChannel(any<NotificationChannel>()) }

        manager.reconcileCriticalAlertBypass(appVersion = "2.4.7")
        verify(exactly = 2) { nm.createNotificationChannel(any<NotificationChannel>()) }
    }

    @Test
    fun `without policy access or a channel there is nothing to reconcile`() = runBlocking {
        channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts", NotificationManager.IMPORTANCE_HIGH)
        assertEquals(CriticalAlertBypass.NOT_APPLICABLE, manager().reconcileCriticalAlertBypass("2.4.6"))
        verify(exactly = 0) { nm.createNotificationChannel(any<NotificationChannel>()) }

        policyAccess = true
        channels.clear()
        assertEquals(CriticalAlertBypass.NOT_APPLICABLE, manager().reconcileCriticalAlertBypass("2.4.6"))
        assertNull(store.data.first()[AppStateKeys.CRITICAL_ALERT_BYPASS_ATTEMPT_VERSION])
    }

    @Test
    fun `a channel that already bypasses is reported enabled without a re-create`() = runBlocking {
        policyAccess = true
        channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts", NotificationManager.IMPORTANCE_HIGH) { setBypassDnd(true) }

        assertEquals(CriticalAlertBypass.ENABLED, manager().reconcileCriticalAlertBypass("2.4.6"))
        verify(exactly = 0) { nm.createNotificationChannel(any<NotificationChannel>()) }
    }

}
