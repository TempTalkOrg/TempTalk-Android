package com.difft.android.chat.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * A mocked [NotificationManager] over an in-memory channel map, served by a spied application
 * context, so both [NotificationChannelManager] and `NotificationChannelSpecs` see the same state.
 */
class FakeChannelNotificationManager {
    val channels = linkedMapOf<String, NotificationChannel>()

    /** Channel ids of the notifications currently shown. */
    val activeChannelIds = mutableListOf<String>()
    val nm: NotificationManager = mockk(relaxed = true)
    val context: Context = spyk(ApplicationProvider.getApplicationContext<Context>())

    init {
        every { nm.getNotificationChannel(any()) } answers { channels[firstArg()] }
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers {
            val channel = firstArg<NotificationChannel>()
            channels.putIfAbsent(channel.id, channel)
        }
        every { nm.deleteNotificationChannel(any()) } answers { channels.remove(firstArg<String>()) }
        every { nm.notificationChannels } answers { channels.values.toList() }
        every { nm.notificationChannelGroups } returns emptyList()
        every { nm.activeNotifications } answers {
            activeChannelIds.map { id ->
                mockk<StatusBarNotification> { every { notification } returns Notification.Builder(context, id).build() }
            }.toTypedArray()
        }
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
    }

    fun channel(id: String, name: String = id, importance: Int = NotificationManager.IMPORTANCE_HIGH, configure: NotificationChannel.() -> Unit = {}) =
        NotificationChannel(id, name, importance).apply(configure).also { channels[id] = it }

    companion object {
        fun store(scope: CoroutineScope, file: File): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }
}
