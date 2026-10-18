package com.difft.android.call.manager

import android.app.Application
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallActionType
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The call page's `onDestroy` cleanup ([CallCleanupManager.cleanup]) releases only that page
 * instance: its lifecycle observer, dialogs, back callback, picture-in-picture manager and unlock
 * receiver, on every destroy. The call is the session's: the cleanup cannot reach the presence, the
 * ongoing-call service, the sounds, the participant observation or the app-unlock listener (it is
 * not handed them), and leaves the projection and the incoming page's control message as they are.
 * Row T5-20 (inverted: call-level teardown is the session's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallCleanupManagerTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `T5-20 a page destroy releases the page instance in order and leaves the call untouched`() {
        val ogsm = OnGoingCallStateManager()
        OgsmTestState.enterCall(ogsm, "r1")
        val incomingSignal = OnGoingCallStateManager.ControlMessage(CallActionType.CALLEND, "r9")
        ogsm.updateControlMessage(incomingSignal)
        val lifecycle = mockk<Lifecycle>(relaxed = true)
        val observer = mockk<CallLifecycleObserver>(relaxed = true)
        val dialogManager = mockk<CallDialogManager>(relaxed = true)
        val pip = mockk<PictureInPictureManager>(relaxed = true)
        val unlockReceiver = mockk<ScreenUnlockBroadcastReceiver>(relaxed = true)
        val backPressed = mockk<OnBackPressedCallback>(relaxed = true) { every { isEnabled } returns true }

        CallCleanupManager(lifecycle = lifecycle, context = app).cleanup(
            lifecycleObserver = observer,
            dialogManager = dialogManager,
            pictureInPictureManager = pip,
            screenUnlockBroadcastReceiver = unlockReceiver,
            backPressedCallback = backPressed,
        )

        verifyOrder {
            lifecycle.removeObserver(observer)
            dialogManager.dismissAll()
            backPressed.remove()
            pip.release()
            unlockReceiver.release(app)
        }
        assertTrue("presence is the session's", ogsm.isInCalling())
        assertEquals("r1", ogsm.getCurrentRoomId())
        assertEquals("the incoming page's signal is kept", incomingSignal, ogsm.controlMessage.value)
        assertTrue("no broadcast", shadowOf(app).broadcastIntents.isEmpty())
    }

    @Test
    fun `one failing release does not stop the others`() {
        val dialogManager = mockk<CallDialogManager>(relaxed = true) {
            every { dismissAll() } throws IllegalStateException("window gone")
        }
        val pip = mockk<PictureInPictureManager>(relaxed = true)
        val unlockReceiver = mockk<ScreenUnlockBroadcastReceiver>(relaxed = true)

        CallCleanupManager(lifecycle = mockk(relaxed = true), context = app).cleanup(
            lifecycleObserver = null,
            dialogManager = dialogManager,
            pictureInPictureManager = pip,
            screenUnlockBroadcastReceiver = unlockReceiver,
            backPressedCallback = null,
        )

        verifyOrder {
            pip.release()
            unlockReceiver.release(app)
        }
    }
}
