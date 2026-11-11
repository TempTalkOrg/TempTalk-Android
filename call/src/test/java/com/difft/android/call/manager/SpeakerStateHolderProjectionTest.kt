package com.difft.android.call.manager

import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.PromptReminder
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.session.EndControllerRig
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.test.TestDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit







class SpeakerStateHolderProjectionTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule(UnconfinedTestDispatcher())

    private val scope = CoroutineScope(SupervisorJob() + dispatcherRule.testDispatcher)
    private val ogsm = OnGoingCallStateManager()
    private val fired = CountDownLatch(1)
    private val roomCtl = mockk<CallRoomController>(relaxed = true) {
        every { updateNoSpeakSoloTimeout(true) } answers { fired.countDown() }
    }
    private val holder = SpeakerStateHolder(scope, roomCtl, ogsm)
    private val config = CallConfig(autoLeave = AutoLeave(PromptReminder(silenceTimeout = 1, soloMemberTimeout = 1)))

    @After
    fun tearDown() {
        holder.cancelJobs()
        scope.cancel()
    }

    @Test
    fun `T5-12 alone in a live call the solo reminder fires`() {
        OgsmTestState.enterCall(ogsm, "r1")

        holder.checkNoSpeakOrOnePersonTimeout(hasRemoteParticipants = false, isSilent = true, callConfig = config)

        assertTrue(fired.await(AWAIT_S, TimeUnit.SECONDS))
    }

    @Test
    fun `T5-12 once the call is ending the solo reminder is never armed`() {
        OgsmTestState.enterCall(ogsm, "r1")
        EndControllerRig(ogsm = ogsm).controller.markEnding()

        holder.checkNoSpeakOrOnePersonTimeout(hasRemoteParticipants = false, isSilent = true, callConfig = config)

        assertTrue("not fired", !fired.await(QUIET_MS, TimeUnit.MILLISECONDS))
        verify(exactly = 0) { roomCtl.updateNoSpeakSoloTimeout(true) }
    }

    private companion object {
        const val AWAIT_S = 5L
        const val QUIET_MS = 300L
    }
}
