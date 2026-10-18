package com.difft.android.call.session

import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.network.NetworkQualityLevel
import com.difft.android.call.network.NetworkQualityView
import com.difft.android.call.ui.barrage.BarrageRenderable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session→UI binding of the five session-written fields: [CallUiController] exposes the
 * session's own `StateFlow` objects, so a session write is visible to the UI with no hop. And the
 * one-shot events: kept for the first subscriber (the first call page), in order and capped, toasts
 * excepted; dropped with no subscriber after that. Rows: T3-02, T3-03.
 */
class CallSessionUiStateTest {

    @Test
    fun `T3-02 the UI controller binds the five session fields by identity and sees writes without a hop`() {
        val state = CallSessionUiState()
        val controller = CallUiController(state)
        val view = NetworkQualityView(local = NetworkQualityLevel.BAD)

        assertSame(state.isShareScreening, controller.isShareScreening)
        assertSame(state.speakingEnabled, controller.speakingEnabled)
        assertSame(state.countDownDurationStr, controller.countDownDurationStr)
        assertSame(state.reconnectCount, controller.reconnectCount)
        assertSame(state.networkQuality, controller.networkQuality)

        state.setShareScreening(true)
        state.setSpeakingEnabled(false)
        state.setCountDownDurationStr("01:00")
        state.incrementReconnectCount()
        state.setNetworkQuality(view)

        assertEquals(true, controller.isShareScreening.value)
        assertEquals(false, controller.speakingEnabled.value)
        assertEquals("01:00", controller.countDownDurationStr.value)
        assertEquals(1, controller.reconnectCount.value)
        assertEquals(view, controller.networkQuality.value)
    }

    @Test
    fun `events emitted before the first subscriber reach it in order, the newest 64 kept`() = runTest {
        val state = CallSessionUiState()
        val emitted = (1..70).map(::barrage)
        emitted.forEach(state::emit)

        val received = mutableListOf<CallSessionUiEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { state.events.collect { received += it } }
        state.emit(CallSessionUiEvent.RevealCallChrome)

        assertEquals(emitted.takeLast(64) + CallSessionUiEvent.RevealCallChrome, received)
        collector.cancel()
    }

    @Test
    fun `after the first subscriber an event with no subscriber is dropped`() = runTest {
        val state = CallSessionUiState()
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { state.events.collect { } }
        first.cancel()

        state.emit(barrage(1))
        val received = mutableListOf<CallSessionUiEvent>()
        val second = launch(UnconfinedTestDispatcher(testScheduler)) { state.events.collect { received += it } }

        assertTrue(received.isEmpty())
        second.cancel()
    }

    @Test
    fun `a toast emitted before the first subscriber is never kept`() = runTest {
        val state = CallSessionUiState()
        val kept = barrage(1)
        state.emit(CallSessionUiEvent.Toast("early"))
        state.emit(kept)

        val received = mutableListOf<CallSessionUiEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { state.events.collect { received += it } }

        assertEquals(listOf(kept), received)
        collector.cancel()
    }

    private fun barrage(i: Int) = CallSessionUiEvent.Barrage(BarrageRenderable.Default(BarrageMessage("u", "m$i", i.toLong())))

    @Test
    fun `T3-03 a bare UI controller starts from the same initial values as before`() {
        val controller = CallUiController()

        assertEquals(true, controller.speakingEnabled.value)
        assertEquals(false, controller.isShareScreening.value)
        assertEquals("00:00", controller.countDownDurationStr.value)
        assertEquals(0, controller.reconnectCount.value)
        assertEquals(NetworkQualityView.NONE, controller.networkQuality.value)
    }
}
