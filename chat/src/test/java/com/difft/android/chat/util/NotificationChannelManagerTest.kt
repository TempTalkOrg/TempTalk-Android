package com.difft.android.chat.util

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.storage.AppStateKeys
import io.mockk.every
import io.mockk.mockk
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Channel creation and upkeep: the five kept channels (#1354), in-place rename that keeps the
 * user's settings, and the critical-alert bypass reconcile. Retirement of obsolete ids is covered
 * by [NotificationChannelMigrationTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var fake: FakeChannelNotificationManager
    private val nm get() = fake.nm
    private val context get() = fake.context
    private var policyAccess = false

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = FakeChannelNotificationManager.store(scope, tmp.newFile("p.preferences_pb"))
        fake = FakeChannelNotificationManager()
        every { nm.isNotificationPolicyAccessGranted } answers { policyAccess }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun manager() = NotificationChannelManager(context, store)

    private fun localized(id: String) = context.getString(NotificationChannelIds.nameRes(id)!!)

    @Test
    fun `a fresh install creates exactly the five kept channels with their specs`() {
        manager().ensureChannels()

        assertEquals(NotificationChannelIds.KEEP.toSet(), fake.channels.keys)
        assertEquals("Calls", fake.channels.getValue("CALL").name.toString())
        assertTrue(fake.channels.getValue("CRITICAL_ALERT_V2").canBypassDnd())
        assertEquals(NotificationChannelIds.MESSAGE_GROUP, fake.channels.getValue("MESSAGE").group)
        val other = fake.channels.getValue(NotificationChannelIds.OTHER_ALERTS)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, other.importance)
        assertEquals("Other alerts", other.name.toString())
        val background = fake.channels.getValue(NotificationChannelIds.RUNNING_IN_BACKGROUND)
        assertEquals(NotificationManager.IMPORTANCE_LOW, background.importance)
        assertNull(background.sound)
        assertFalse(background.shouldVibrate())
        verify { nm.createNotificationChannelGroup(match<NotificationChannelGroup> { it.id == NotificationChannelIds.MESSAGE_GROUP }) }
    }

    @Test
    fun `ensuring every channel through every entry point creates no obsolete id`() {
        manager().ensureChannels()
        NotificationChannelSpecs.ensure(context, NotificationChannelIds.OTHER_ALERTS)
        NotificationChannelSpecs.ensure(context, NotificationChannelIds.RUNNING_IN_BACKGROUND)
        NotificationChannelSpecs.foregroundChannel(context)
        manager().ensureMessageChannel()

        assertEquals(emptySet(), fake.channels.keys intersect NotificationChannelIds.OBSOLETE.toSet())
    }

    // The startup path and the shared ensure never touch DataStore.
    @Test
    fun `ensuring channels never reads DataStore`() {
        val guarded = mockk<DataStore<Preferences>> {
            every { data } answers { throw AssertionError("DataStore read on the channel ensure path") }
        }

        NotificationChannelManager(context, guarded).ensureChannels()
        NotificationChannelManager(context, guarded).ensureMessageChannel()
        NotificationChannelSpecs.ensure(context, NotificationChannelIds.OTHER_ALERTS)

        assertEquals(NotificationChannelIds.KEEP.toSet(), fake.channels.keys)
    }

    @Test
    fun `an existing channel is renamed in place without lowering or resetting anything`() {
        fake.channel(NotificationChannelIds.MESSAGE, "MESSAGE", NotificationManager.IMPORTANCE_LOW)
        fake.channel(NotificationChannelIds.CRITICAL_ALERT, "CRITICAL_ALERT_V2") { setBypassDnd(true) }
        listOf(NotificationChannelIds.CALL, NotificationChannelIds.OTHER_ALERTS, NotificationChannelIds.RUNNING_IN_BACKGROUND)
            .forEach { id -> fake.channel(id, localized(id), NotificationManager.IMPORTANCE_DEFAULT) }
        val submitted = mutableListOf<NotificationChannel>()
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { submitted += firstArg<NotificationChannel>() }

        manager().ensureChannels()

        assertEquals(setOf("MESSAGE", "CRITICAL_ALERT_V2"), submitted.map { it.id }.toSet())
        val message = submitted.single { it.id == "MESSAGE" }
        assertEquals("Messages", message.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, message.importance)
        assertEquals(NotificationChannelIds.MESSAGE_GROUP, message.group)
        val critical = submitted.single { it.id == "CRITICAL_ALERT_V2" }
        assertEquals("Critical alerts", critical.name.toString())
        assertTrue(critical.canBypassDnd())
        verify(exactly = 0) { nm.deleteNotificationChannel(any()) }
    }

    @Test
    fun `a renamed channel keeps its own group and others stay ungrouped`() {
        fake.channel(NotificationChannelIds.MESSAGE, "MESSAGE") { group = "SOME_OTHER_GROUP" }
        fake.channel(NotificationChannelIds.CALL, "CALL")
        val submitted = mutableListOf<NotificationChannel>()
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { submitted += firstArg<NotificationChannel>() }

        manager().ensureChannels()

        assertEquals("SOME_OTHER_GROUP", submitted.single { it.id == "MESSAGE" }.group)
        assertNull(submitted.single { it.id == "CALL" }.group)
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh")
    fun `the localized name follows the app language`() {
        fake.channel(NotificationChannelIds.CALL, "CALL")
        fake.channel(NotificationChannelIds.RUNNING_IN_BACKGROUND, "Running in background", NotificationManager.IMPORTANCE_LOW)
        val submitted = mutableListOf<NotificationChannel>()
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { submitted += firstArg<NotificationChannel>() }

        manager().ensureChannels()

        assertEquals("来电", submitted.single { it.id == "CALL" }.name.toString())
        assertEquals("后台运行", submitted.single { it.id == NotificationChannelIds.RUNNING_IN_BACKGROUND }.name.toString())
        assertEquals("其他提醒", submitted.single { it.id == NotificationChannelIds.OTHER_ALERTS }.name.toString())
    }

    @Test
    fun `a channel already carrying its localized name is not touched`() {
        NotificationChannelIds.KEEP.forEach { id -> fake.channel(id, localized(id)) }
        every { nm.notificationChannelGroups } returns listOf(
            NotificationChannelGroup(NotificationChannelIds.MESSAGE_GROUP, localized(NotificationChannelIds.MESSAGE_GROUP))
        )

        manager().ensureChannels()

        verify(exactly = 0) { nm.createNotificationChannel(any<NotificationChannel>()) }
        verify(exactly = 0) { nm.createNotificationChannelGroup(any<NotificationChannelGroup>()) }
    }

    @Test
    fun `a throwing notification manager never escapes the ensure paths`() {
        every { nm.getNotificationChannel(any()) } throws RuntimeException("dead system")
        every { nm.notificationChannelGroups } throws RuntimeException("dead system")

        manager().ensureChannels()

        assertFalse(manager().ensureMessageChannel())
    }

    @Test
    fun `the message channel is re-created with its group before a post`() {
        assertTrue(manager().ensureMessageChannel())

        assertEquals(NotificationChannelIds.MESSAGE_GROUP, fake.channels.getValue(NotificationChannelIds.MESSAGE).group)
        verify { nm.createNotificationChannelGroup(any<NotificationChannelGroup>()) }
    }

    @Test
    @Config(sdk = [26])
    fun `a fresh API 26 install creates the group before the channels without the API 28 lookup`() {
        val groups = mutableListOf<NotificationChannelGroup>()
        every { nm.createNotificationChannelGroup(any<NotificationChannelGroup>()) } answers { groups += firstArg<NotificationChannelGroup>() }

        manager().ensureChannels()

        assertEquals(listOf(NotificationChannelIds.MESSAGE_GROUP), groups.map { it.id })
        assertTrue(fake.channels.keys.containsAll(NotificationChannelIds.KEEP))
    }

    @Test
    fun `an already-authorised user is reconciled on the first foreground and the result is read back`() = runBlocking {
        policyAccess = true
        fake.channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts")
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
        val old = fake.channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts")
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers { old.setBypassDnd(true) }

        assertEquals(CriticalAlertBypass.ENABLED, manager().reconcileCriticalAlertBypass(appVersion = "2.4.6"))
    }

    @Test
    fun `the re-create is attempted at most once per app version`() = runBlocking {
        policyAccess = true
        fake.channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts")
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
        fake.channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts")
        assertEquals(CriticalAlertBypass.NOT_APPLICABLE, manager().reconcileCriticalAlertBypass("2.4.6"))
        verify(exactly = 0) { nm.createNotificationChannel(any<NotificationChannel>()) }

        policyAccess = true
        fake.channels.clear()
        assertEquals(CriticalAlertBypass.NOT_APPLICABLE, manager().reconcileCriticalAlertBypass("2.4.6"))
        assertNull(store.data.first()[AppStateKeys.CRITICAL_ALERT_BYPASS_ATTEMPT_VERSION])
    }

    @Test
    fun `a channel that already bypasses is reported enabled without a re-create`() = runBlocking {
        policyAccess = true
        fake.channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts") { setBypassDnd(true) }

        assertEquals(CriticalAlertBypass.ENABLED, manager().reconcileCriticalAlertBypass("2.4.6"))
        verify(exactly = 0) { nm.createNotificationChannel(any<NotificationChannel>()) }
    }
}
