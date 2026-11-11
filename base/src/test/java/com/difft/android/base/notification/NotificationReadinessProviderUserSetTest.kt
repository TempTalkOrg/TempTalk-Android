package com.difft.android.base.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals


@RunWith(RobolectricTestRunner::class)
class NotificationReadinessProviderUserSetTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun createCallChannel(): NotificationChannel {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(NotificationChannelIds.CALL, "Calls", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(channel)
        return nm.getNotificationChannel(NotificationChannelIds.CALL)
    }

    private fun readCall() = NotificationReadinessProvider(context).readInput().callChannel

    @Test
    @Config(sdk = [34])
    fun `a category the user never set reads as not set on API 29 and above`() {
        createCallChannel()
        assertEquals(false, readCall()?.userSetImportance)
    }

    @Test
    @Config(sdk = [34])
    fun `a category whose importance the user locked reads as set`() {
        val channel = createCallChannel()
        
        NotificationChannel::class.java.getMethod("lockFields", Int::class.javaPrimitiveType).invoke(channel, USER_LOCKED_IMPORTANCE)
        assertEquals(true, readCall()?.userSetImportance)
    }

    @Test
    @Config(sdk = [28])
    fun `below API 29 a category always reads as set`() {
        createCallChannel()
        assertEquals(true, readCall()?.userSetImportance)
    }

    private companion object {
        const val USER_LOCKED_IMPORTANCE = 0x00000004
    }
}
