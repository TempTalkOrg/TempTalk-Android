package com.difft.android.chat.util

import android.app.NotificationManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.storage.AppStateKeys
import io.mockk.every
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

/** #1354: retiring the obsolete channel ids at startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var fake: FakeChannelNotificationManager
    private val nm get() = fake.nm

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = FakeChannelNotificationManager.store(scope, tmp.newFile("p.preferences_pb"))
        fake = FakeChannelNotificationManager()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun manager() = NotificationChannelManager(fake.context, store)

    private suspend fun version() = store.data.first()[AppStateKeys.NOTIFICATION_CHANNEL_VERSION]

    private suspend fun setVersion(value: Int) = store.edit { it[AppStateKeys.NOTIFICATION_CHANNEL_VERSION] = value }

    private fun presetAllObsolete() = NotificationChannelIds.OBSOLETE.forEach { fake.channel(it) }

    private val survivors = NotificationChannelIds.KEEP.toSet() + setOf("MESSAGEabc", "fcm_fallback_notification_channel", "some_lib_channel")

    private fun presetUpgradeDevice() {
        presetAllObsolete()
        fake.channel("MESSAGEabc") { setConversationId(NotificationChannelIds.MESSAGE, "abc") }
        fake.channel("fcm_fallback_notification_channel")
        fake.channel("some_lib_channel")
    }

    @Test
    fun `an upgrade from before #1352 leaves the kept, per-conversation and third-party channels only`() = runBlocking {
        presetUpgradeDevice()
        val manager = manager()

        manager.ensureChannels()
        manager.migrateIfNeeded()

        assertEquals(survivors, fake.channels.keys)
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
        // The unknown id was logged once and is not re-logged.
        assertEquals(true, store.data.first()[AppStateKeys.NOTIFICATION_CHANNEL_UNKNOWN_LOGGED])
        NotificationChannelIds.OBSOLETE.forEach { id -> verify(exactly = 1) { nm.deleteNotificationChannel(id) } }
        verify(exactly = NotificationChannelIds.OBSOLETE.size) { nm.deleteNotificationChannel(any()) }

        manager.migrateIfNeeded()
        verify(exactly = NotificationChannelIds.OBSOLETE.size) { nm.deleteNotificationChannel(any()) }
    }

    @Test
    fun `an upgrade from #1352 needs no lowering pass and deletes the obsolete ids`() = runBlocking {
        setVersion(1)
        presetUpgradeDevice()
        fake.channels.getValue("ONGOING_CALL").importance = NotificationManager.IMPORTANCE_DEFAULT

        manager().migrateIfNeeded()

        assertEquals(survivors - NotificationChannelIds.KEEP.toSet(), fake.channels.keys)
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
        verify(exactly = 0) { nm.createNotificationChannel(any()) }
    }

    @Test
    fun `kept channels keep the user's settings`() = runBlocking {
        val message = fake.channel(NotificationChannelIds.MESSAGE, "Messages", NotificationManager.IMPORTANCE_LOW) {
            group = NotificationChannelIds.MESSAGE_GROUP
            setSound(null, null)
        }
        val critical = fake.channel(NotificationChannelIds.CRITICAL_ALERT, "Critical alerts") { setBypassDnd(true) }
        presetAllObsolete()

        manager().migrateIfNeeded()

        assertTrue(fake.channels.getValue(NotificationChannelIds.MESSAGE) === message)
        assertEquals(NotificationManager.IMPORTANCE_LOW, message.importance)
        assertNull(message.sound)
        assertEquals(NotificationChannelIds.MESSAGE_GROUP, message.group)
        assertTrue(fake.channels.getValue(NotificationChannelIds.CRITICAL_ALERT) === critical)
        assertTrue(critical.canBypassDnd())
        NotificationChannelIds.KEEP.forEach { id -> verify(exactly = 0) { nm.deleteNotificationChannel(id) } }
    }

    @Test
    fun `a delete refused for a foreground service is deferred and retried on the next start`() = runBlocking {
        presetAllObsolete()
        every { nm.deleteNotificationChannel("ONGOING_CALL") } throws SecurityException("fgs")
        val manager = manager()

        manager.migrateIfNeeded()

        assertEquals(setOf("ONGOING_CALL"), fake.channels.keys)
        assertNull(version())

        every { nm.deleteNotificationChannel("ONGOING_CALL") } answers { fake.channels.remove("ONGOING_CALL") }
        manager.migrateIfNeeded()

        assertEquals(emptySet(), fake.channels.keys)
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
    }

    // Below API 31 the summary channel is left while a summary sits on it.
    @Test
    @Config(sdk = [26, 30])
    fun `below API 31 an active summary defers the summary channel delete`() = runBlocking {
        presetAllObsolete()
        fake.activeChannelIds += NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY
        val manager = manager()

        manager.migrateIfNeeded()

        verify(exactly = 0) { nm.deleteNotificationChannel(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY) }
        assertEquals(setOf(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY), fake.channels.keys)
        assertNull(version())

        fake.activeChannelIds.clear()
        manager.migrateIfNeeded()

        verify(exactly = 1) { nm.deleteNotificationChannel(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY) }
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
    }

    @Test
    @Config(sdk = [30])
    fun `below API 31 a message notification on another channel does not hold the summary channel`() = runBlocking {
        fake.channel(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY)
        fake.activeChannelIds += NotificationChannelIds.MESSAGE

        manager().migrateIfNeeded()

        verify(exactly = 1) { nm.deleteNotificationChannel(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY) }
    }

    @Test
    @Config(sdk = [31])
    fun `from API 31 the summary channel is deleted even with a summary showing`() = runBlocking {
        fake.channel(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY)
        fake.activeChannelIds += NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY

        manager().migrateIfNeeded()

        verify(exactly = 1) { nm.deleteNotificationChannel(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY) }
        verify(exactly = 0) { nm.activeNotifications }
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
    }

    @Test
    fun `a run killed half-way is finished by the next start and then does nothing`() = runBlocking {
        listOf("ACCOUNT", "EVENT", "app_upgrade").forEach { fake.channel(it) }
        val manager = manager()

        manager.migrateIfNeeded()
        assertEquals(emptySet(), fake.channels.keys)
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
        verify(exactly = 3) { nm.deleteNotificationChannel(any()) }

        manager.migrateIfNeeded()
        verify(exactly = 3) { nm.deleteNotificationChannel(any()) }
    }

    @Test
    fun `an obsolete id re-created by a downgrade is deleted again without touching the version`() = runBlocking {
        setVersion(NotificationChannelManager.CHANNEL_VERSION)
        fake.channel(NotificationChannelIds.OBSOLETE_BACKGROUND, importance = NotificationManager.IMPORTANCE_NONE)

        manager().ensureChannels()
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
        manager().migrateIfNeeded()

        verify(exactly = 1) { nm.deleteNotificationChannel(NotificationChannelIds.OBSOLETE_BACKGROUND) }
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
    }

    @Test
    fun `an unexpected delete error is not deferred and the version is stamped`() = runBlocking {
        presetAllObsolete()
        every { nm.deleteNotificationChannel("EVENT") } throws IllegalArgumentException("odd")

        manager().migrateIfNeeded()

        assertEquals(setOf("EVENT"), fake.channels.keys)
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
    }

    // Ensures racing the deletes never lose a kept channel, and only obsolete ids are deleted.
    @Test
    fun `ensures interleaved with the migration keep every kept channel`() = runBlocking {
        presetAllObsolete()
        val manager = manager()
        val deleted = mutableListOf<String>()
        every { nm.deleteNotificationChannel(any()) } answers {
            val id = firstArg<String>()
            deleted += id
            fake.channels.remove(id)
            if (deleted.size == NotificationChannelIds.OBSOLETE.size / 2) {
                NotificationChannelSpecs.ensure(fake.context, NotificationChannelIds.OTHER_ALERTS)
                NotificationChannelSpecs.foregroundChannel(fake.context)
                manager.ensureChannels()
            }
        }

        manager.migrateIfNeeded()

        assertEquals(NotificationChannelIds.KEEP.toSet(), fake.channels.keys)
        assertTrue(deleted.all { it in NotificationChannelIds.OBSOLETE })
        assertEquals(NotificationChannelIds.OBSOLETE.toSet(), deleted.toSet())
    }

    @Test
    fun `a throwing channel listing skips the run without stamping`() = runBlocking {
        presetAllObsolete()
        every { nm.notificationChannels } throws RuntimeException("dead system")

        manager().migrateIfNeeded()

        verify(exactly = 0) { nm.deleteNotificationChannel(any()) }
        assertNull(version())
    }

    @Test
    @Config(sdk = [30])
    fun `a throwing active-notification lookup defers the summary channel`() = runBlocking {
        fake.channel(NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY)
        every { nm.activeNotifications } throws RuntimeException("dead system")

        manager().migrateIfNeeded()

        verify(exactly = 0) { nm.deleteNotificationChannel(any()) }
        assertNull(version())
    }

    @Test
    fun `nothing obsolete on a fresh install still stamps the version once`() = runBlocking {
        manager().ensureChannels()

        manager().migrateIfNeeded()

        verify(exactly = 0) { nm.deleteNotificationChannel(any()) }
        assertEquals(NotificationChannelManager.CHANNEL_VERSION, version())
        assertNull(store.data.first()[AppStateKeys.NOTIFICATION_CHANNEL_UNKNOWN_LOGGED])
    }
}
