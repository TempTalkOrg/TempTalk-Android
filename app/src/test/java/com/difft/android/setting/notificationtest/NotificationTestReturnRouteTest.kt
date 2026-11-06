package com.difft.android.setting.notificationtest

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.difft.android.MainActivity
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOwner
import com.difft.android.base.notification.NotificationTestRun
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTestReturnRouteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun run(host: NotificationTestOwner.Host) = NotificationTestRun(
        UUID.randomUUID().toString(), NotificationTestOwner(UUID.randomUUID().toString(), host), NotificationTestKind.BANNER,
    )

    @Test
    fun `hosted return preserves exact identity through Main forwarding without clearing home`() {
        val run = run(NotificationTestOwner.Host.HOSTED)
        val input = NotificationTestReturnRoute.returnIntent(context, run)
        assertEquals(MainActivity::class.java.name, input.component?.className)
        assertTrue(input.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, input.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val output = Intent()
        NotificationTestReturnRoute.forward(input, output)
        assertEquals(NotificationTestReturnRoute.of(run), NotificationTestReturnRoute.read(output))
        assertEquals(input.data, output.data)
    }

    @Test
    fun `shell return and all ordinary shell starts share app task reuse flags`() {
        val run = run(NotificationTestOwner.Host.SHELL)
        val input = NotificationTestReturnRoute.returnIntent(context, run)
        val ordinary = NotificationTestDestination.shellIntent(context)
        assertEquals(NotificationTestActivity::class.java.name, input.component?.className)
        assertEquals(ordinary.flags, input.flags)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, input.flags)
        assertEquals(0, input.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }

    @Test
    fun `unclassified malformed and mismatched return payloads are rejected`() {
        val run = run(NotificationTestOwner.Host.HOSTED)
        val intent = NotificationTestReturnRoute.returnIntent(context, run)
        intent.removeCategory(NotificationTestReturnRoute.CATEGORY)
        assertNull(NotificationTestReturnRoute.read(intent))
        NotificationTestReturnRoute.of(run).applyTo(intent)
        intent.data = android.net.Uri.parse("temptalk-internal://notification-test/return/wrong")
        assertNull(NotificationTestReturnRoute.read(intent))
        NotificationTestReturnRoute("bad-owner", run.runId, run.kind).applyTo(intent)
        assertNull(NotificationTestReturnRoute.read(intent))
    }

    @Test
    fun `new runs have distinct pending intent URI identity even for the same owner`() {
        val first = run(NotificationTestOwner.Host.HOSTED)
        val next = first.copy(runId = UUID.randomUUID().toString())
        assertNotEquals(NotificationTestReturnRoute.returnIntent(context, first).data, NotificationTestReturnRoute.returnIntent(context, next).data)
    }
}
