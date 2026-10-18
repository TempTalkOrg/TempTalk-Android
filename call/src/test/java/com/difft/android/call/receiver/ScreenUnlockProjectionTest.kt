package com.difft.android.call.receiver

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import util.ScreenLockUtil

/**
 * The unlock receiver brings the call page back to the front from the projection a real session
 * writes (`isInCalling` through `beginPresence` / `endPresence`) combined with the page's window flags
 * (`isInForeground`, `isInPipMode`, reset when the last call page goes away). Row T5-07 (L07, L08).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ScreenUnlockProjectionTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ogsm = OnGoingCallStateManager()
    private val publisher = OngoingCallPublisher(SESSION_ID, ogsm).apply { bind() }
    private var broughtToFront = 0
    private val receiver = ScreenUnlockBroadcastReceiver(onBringCallActivityToFront = { broughtToFront++ }, ogsm)
    private val unlockFlagBefore = ScreenLockUtil.temporarilyDisabled

    @Before
    fun setUp() {
        publisher.beginPresence()
        ogsm.setIsInForeground(false)
        receiver.register(app)
    }

    @After
    fun tearDown() {
        ScreenLockUtil.temporarilyDisabled = unlockFlagBefore
    }

    @Test
    fun `T5-07 unlock with the call in the background brings the page to the front once`() {
        unlock()

        assertEquals(1, broughtToFront)
    }

    @Test
    fun `T5-07 unlock while in picture-in-picture does nothing`() {
        ogsm.setIsInPipMode(true)

        unlock()

        assertEquals(0, broughtToFront)
    }

    @Test
    fun `T5-07 unlock after the session ended its presence does nothing`() {
        publisher.endPresence()

        unlock()

        assertEquals(0, broughtToFront)
    }

    @Test
    fun `unlock after the last page left picture-in-picture by going away brings the call to the front`() {
        ogsm.callPageAttached()
        ogsm.setIsInPipMode(true)

        ogsm.callPageDetached()
        unlock()

        assertEquals("no stale picture-in-picture flag", 1, broughtToFront)
    }

    private fun unlock() {
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val SESSION_ID = 1L
    }
}
