package com.difft.android.call.cleanup

import com.difft.android.call.LCallEngine
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.feedback.CallFeedbackBinder
import com.difft.android.call.handler.CallTimeoutMonitor
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.manager.CallStatisticsLogManager
import com.difft.android.call.manager.SpeakerStateHolder
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.media.CallAudioSetup
import com.difft.android.call.network.NetworkQualityCoordinator
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.CallTypeCoordinator
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import io.livekit.android.audio.AudioSwitchHandler
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallCleanupStepsTest {

    @Test
    fun `dispatcher jobs are cancelled before room release and timer stop`() {
        val stepNames = CallCleanupSteps.build(
            sessionId = 1L,
            audioProcessor = mockk<AudioPipelineProcessor>(relaxed = true),
            audioHandler = mockk<AudioSwitchHandler>(relaxed = true),
            audioSetup = mockk<CallAudioSetup>(relaxed = true),
            timerManager = mockk<TimerManager>(relaxed = true),
            timeoutMonitor = mockk<CallTimeoutMonitor>(relaxed = true),
            roomCtl = mockk<CallRoomController>(relaxed = true),
            speakerState = mockk<SpeakerStateHolder>(relaxed = true),
            screenShareFloatingSpeakerStateHolder = null,
            screenSharePreWarmer = mockk<ScreenSharePreWarmer>(relaxed = true),
            roomEventDispatcher = mockk<RoomEventDispatcher>(relaxed = true),
            callTypeCoordinator = mockk<CallTypeCoordinator>(relaxed = true),
            networkQualityCoordinator = mockk<NetworkQualityCoordinator>(relaxed = true),
            statisticsLogManager = mockk<CallStatisticsLogManager>(relaxed = true),
            feedbackBinder = mockk<CallFeedbackBinder>(relaxed = true),
            shouldTriggerFeedbackView = {},
            clearE2eeKey = {},
        ).map { it.name }

        fun requiredStepIndex(name: String): Int {
            val index = stepNames.indexOf(name)
            assertTrue("Cleanup step '$name' must exist", index >= 0)
            return index
        }

        val cancelDispatcherIndex = requiredStepIndex("cancelRoomEventDispatcher")
        val disconnectAndReleaseIndex = requiredStepIndex("roomCtl.disconnectAndRelease")
        val stopTimersIndex = requiredStepIndex("stopTimers")
        assertTrue(cancelDispatcherIndex < disconnectAndReleaseIndex)
        assertTrue(cancelDispatcherIndex < stopTimersIndex)

        // The weak-network tick reads the room's quality providers, so it has to be stopped before
        // the room is released.
        val resetNetworkQualityIndex = requiredStepIndex("resetNetworkQuality")
        assertTrue(resetNetworkQualityIndex < disconnectAndReleaseIndex)

        // The ongoing-call service belongs to the session's own end, keyed by its id.
        assertTrue("no service step", stepNames.none { it.contains("Service") })
    }

    @Test
    fun `the process-wide slots are released for this session only`() = runTest {
        val statistics = mockk<CallStatisticsLogManager>(relaxed = true)
        val steps = buildSteps(sessionId = 1L, statistics = statistics).associateBy { it.name }
        LCallEngine.claimConnectedServerUrl(2L)
        LCallEngine.setConnectedServerUrl("wss://newer-call")

        steps.getValue("flushStatisticsLogs").action()
        steps.getValue("clearConnectedServerUrl").action()

        verify(exactly = 1) { statistics.releaseRoom(1L) }
        verify(exactly = 0) { statistics.setRoomId(any()) }
        assertEquals("a newer call's URL is kept", "wss://newer-call", LCallEngine.serverUrlConnected.value)

        buildSteps(sessionId = 2L, statistics = statistics).single { it.name == "clearConnectedServerUrl" }.action()
        assertNull(LCallEngine.serverUrlConnected.value)
    }

    private fun buildSteps(sessionId: Long, statistics: CallStatisticsLogManager) = CallCleanupSteps.build(
        sessionId = sessionId,
        audioProcessor = mockk(relaxed = true),
        audioHandler = mockk(relaxed = true),
        audioSetup = mockk(relaxed = true),
        timerManager = mockk(relaxed = true),
        timeoutMonitor = mockk(relaxed = true),
        roomCtl = mockk(relaxed = true),
        speakerState = mockk(relaxed = true),
        screenShareFloatingSpeakerStateHolder = null,
        screenSharePreWarmer = mockk(relaxed = true),
        roomEventDispatcher = null,
        callTypeCoordinator = null,
        networkQualityCoordinator = null,
        statisticsLogManager = statistics,
        feedbackBinder = mockk(relaxed = true),
        shouldTriggerFeedbackView = {},
        clearE2eeKey = {},
    )
}
