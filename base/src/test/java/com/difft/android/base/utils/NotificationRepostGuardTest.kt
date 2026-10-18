package com.difft.android.base.utils

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.service.notification.StatusBarNotification
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [NotificationRepostGuard] (#1347). `nm.cancel` is applied asynchronously by the system, so the
 * mocked manager keeps listing a cancelled id as active, which is exactly the window the tombstone
 * has to cover (R1-F3).
 */
@RunWith(RobolectricTestRunner::class)
class NotificationRepostGuardTest {

    private val id = 4242
    private lateinit var nm: NotificationManager
    private lateinit var notification: Notification
    private lateinit var sbn: StatusBarNotification
    private var now = 1_000L
    private lateinit var guard: NotificationRepostGuard

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        nm = mockk(relaxed = true)
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.NOTIFICATION_SERVICE) nm else super.getSystemService(name)
        }
        notification = Notification.Builder(app, "test-channel")
            .setContentTitle("call")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        sbn = mockk()
        every { sbn.id } returns id
        every { sbn.tag } returns null
        every { sbn.notification } returns notification
        every { nm.activeNotifications } returns arrayOf(sbn)
        guard = NotificationRepostGuard(context) { now }
    }

    @Test
    fun `active notification is re-posted silently with the same id`() {
        val posted = slot<Notification>()
        every { nm.notify(null as String?, id, capture(posted)) } returns Unit

        assertTrue(guard.repostIfActive(id))

        verify(exactly = 1) { nm.notify(null, id, any()) }
        assertTrue((posted.captured.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
    }

    @Test
    fun `a timed notification keeps its original expiry on repost`() {
        withTimedNotification(timeoutMs = 60_000L, postTime = now - 50_000L)
        val posted = slot<Notification>()
        every { nm.notify(null as String?, id, capture(posted)) } returns Unit

        assertTrue(guard.repostIfActive(id))

        assertEquals(10_000L, posted.captured.timeoutAfter)
    }

    @Test
    fun `a timed notification at its expiry is not re-posted`() {
        withTimedNotification(timeoutMs = 60_000L, postTime = now - 60_000L)

        assertFalse(guard.repostIfActive(id))

        verify(exactly = 0) { nm.notify(any<String>(), any<Int>(), any<android.app.Notification>()) }
    }

    @Test
    fun `a wall clock behind the post time never extends the timeout`() {
        withTimedNotification(timeoutMs = 60_000L, postTime = now + 3_600_000L)
        val posted = slot<Notification>()
        every { nm.notify(null as String?, id, capture(posted)) } returns Unit

        assertTrue(guard.repostIfActive(id))

        assertEquals(60_000L, posted.captured.timeoutAfter)
    }

    private fun withTimedNotification(timeoutMs: Long, postTime: Long) {
        val timed = Notification.Builder(RuntimeEnvironment.getApplication(), "test-channel")
            .setContentTitle("alert")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setTimeoutAfter(timeoutMs)
            .build()
        every { sbn.notification } returns timed
        every { sbn.postTime } returns postTime
    }

    @Test
    fun `inactive id is never posted`() {
        every { nm.activeNotifications } returns emptyArray()

        assertFalse(guard.repostIfActive(id))

        verify(exactly = 0) { nm.notify(any<String>(), any<Int>(), any<android.app.Notification>()) }
    }

    @Test
    fun `R1-F3 cancel while the system still lists the id as active blocks the repost`() {
        guard.cancel(id)

        assertFalse(guard.repostIfActive(id))

        verify(exactly = 1) { nm.cancel(id) }
        verify(exactly = 0) { nm.notify(any<String>(), any<Int>(), any<android.app.Notification>()) }
    }

    @Test
    fun `R1-F3 a cancel racing a repost waits for it and then wins`() {
        val cancelFinished = AtomicBoolean(false)
        val finishedWhileLockHeld = AtomicBoolean(true)
        val canceller = Thread {
            guard.cancel(id)
            cancelFinished.set(true)
        }
        // Start the cancel from inside the repost's active check, i.e. while the guard lock is held.
        every { nm.activeNotifications } answers {
            canceller.start()
            canceller.join(200)
            finishedWhileLockHeld.set(cancelFinished.get())
            arrayOf(sbn)
        }

        assertTrue(guard.repostIfActive(id))
        canceller.join(2_000)
        assertFalse("cancel must block on the guard lock held by repost", finishedWhileLockHeld.get())
        assertTrue(cancelFinished.get())

        every { nm.activeNotifications } returns arrayOf(sbn)
        assertFalse(guard.repostIfActive(id))
        verify(exactly = 1) { nm.notify(null, id, any()) }
    }

    @Test
    fun `R2-F3 fresh post after a cancel allows refreshing the same id again`() {
        guard.cancel(id)
        assertFalse(guard.repostIfActive(id))

        guard.onPosted(id)

        assertTrue(guard.repostIfActive(id))
        verify(exactly = 1) { nm.notify(null, id, any()) }
    }

    @Test
    fun `tombstone expires after the ttl`() {
        guard.cancel(id)
        now += NotificationRepostGuard.TOMBSTONE_TTL_MS - 1
        assertFalse(guard.repostIfActive(id))

        now += 2
        assertTrue(guard.repostIfActive(id))
    }

    @Test
    fun `tombstone is per id`() {
        guard.cancel(id + 1)

        assertTrue(guard.repostIfActive(id))
    }

    @Test
    fun `a swipe after a racing repost cancels the restored notification and blocks later reposts`() {
        assertTrue(guard.repostIfActive(id)) // the late FCM read the alert just before the swipe

        guard.cancel(id) // swipe delete intent

        verify(exactly = 1) { nm.cancel(id) }
        assertFalse(guard.repostIfActive(id))
    }

    @Test
    fun `a second repost keeps counting down from the first expiry`() {
        withTimedNotification(timeoutMs = 60_000L, postTime = now - 20_000L)
        val posted = mutableListOf<Notification>()
        every { nm.notify(null as String?, id, capture(posted)) } returns Unit

        assertTrue(guard.repostIfActive(id))
        // The system now shows the reposted notification: reduced timeout, postTime not reliable.
        now += 10_000L
        withTimedNotification(timeoutMs = 40_000L, postTime = now)
        assertTrue(guard.repostIfActive(id))

        assertEquals(40_000L, posted[0].timeoutAfter)
        assertEquals(30_000L, posted[1].timeoutAfter)
    }

    @Test
    fun `cancelAll tombstones active ids`() {
        guard.cancelAll()

        verify(exactly = 1) { nm.cancelAll() }
        assertFalse(guard.repostIfActive(id))
    }

    @Test
    fun `notify failure is swallowed`() {
        every { nm.notify(any<String>(), any<Int>(), any<android.app.Notification>()) } throws SecurityException("boom")

        assertFalse(guard.repostIfActive(id))
    }
}
