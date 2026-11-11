package com.difft.android.call.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.difft.android.call.data.EmojiBubbleMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference








@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30])
class BubbleOverlayDeferralIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    
    private val emitterScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @After
    fun tearDown() {
        emitterScope.cancel()
    }

    


    private fun buildBubbleFlow(): Pair<MutableSharedFlow<EmojiBubbleMessage>, SharedFlow<EmojiBubbleMessage>> {
        val flow = MutableSharedFlow<EmojiBubbleMessage>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        return flow to flow.asSharedFlow()
    }

    



    @Composable
    private fun DeferredBubbleOverlayHostUnderTest(
        deferralMs: Long,
        bubbles: SharedFlow<EmojiBubbleMessage>,
        onBubbleHostMounted: () -> Unit,
        onBubbleReceived: (EmojiBubbleMessage) -> Unit,
    ) {
        var bubbleHostReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos {  }
            delay(deferralMs)
            bubbleHostReady = true
        }
        if (bubbleHostReady) {
            
            LaunchedEffect(Unit) {
                onBubbleHostMounted()
                bubbles.collect { onBubbleReceived(it) }
            }
            
            Box(modifier = Modifier.fillMaxSize())
        }
    }

    private fun bubble(emoji: String, id: Long): EmojiBubbleMessage =
        EmojiBubbleMessage(
            emoji = emoji,
            userName = "test",
            startOffsetPercent = 50,
            durationMillis = 1000L,
            id = id,
        )

    
    @Test
    fun host_not_mounted_before_deferral_expires() {
        val (_, bubbles) = buildBubbleFlow()
        val mountedCounter = AtomicInteger(0)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            DeferredBubbleOverlayHostUnderTest(
                deferralMs = 100L,
                bubbles = bubbles,
                onBubbleHostMounted = { mountedCounter.incrementAndGet() },
                onBubbleReceived = {  },
            )
        }

        
        composeTestRule.mainClock.advanceTimeBy(50L)
        composeTestRule.waitForIdle()

        assertEquals(
            "Host must not be mounted before deferral expires",
            0,
            mountedCounter.get(),
        )
    }

    
    @Test
    fun host_mounted_after_deferral_expires() {
        val (_, bubbles) = buildBubbleFlow()
        val mountedCounter = AtomicInteger(0)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            DeferredBubbleOverlayHostUnderTest(
                deferralMs = 100L,
                bubbles = bubbles,
                onBubbleHostMounted = { mountedCounter.incrementAndGet() },
                onBubbleReceived = {  },
            )
        }

        
        
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        assertTrue(
            "Host must be mounted after deferral expires (got mountedCounter=${mountedCounter.get()})",
            mountedCounter.get() >= 1,
        )
    }

    



    @Test
    fun bubble_emitted_during_deferral_window_is_dropped() {
        val (mutableFlow, bubbles) = buildBubbleFlow()
        val received = AtomicReference<EmojiBubbleMessage?>(null)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            DeferredBubbleOverlayHostUnderTest(
                deferralMs = 100L,
                bubbles = bubbles,
                onBubbleHostMounted = {  },
                onBubbleReceived = { received.set(it) },
            )
        }

        
        composeTestRule.mainClock.advanceTimeBy(30L)

        
        emitterScope.launch {
            mutableFlow.emit(bubble(emoji = "in-window", id = 30L))
        }

        
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        val bubble = received.get()
        assertFalse(
            "In-window-drop trade-off violated: a bubble emitted at t=30ms inside the " +
                "deferral window WAS delivered (id=${bubble?.id}). This means someone " +
                "re-introduced replay=1 on the SharedFlow — see Design Option E for why " +
                "that causes stale-bubble replay on rotation/PiP exit.",
            bubble?.emoji == "in-window",
        )
    }

    
    @Test
    fun bubble_emitted_after_host_mounts_is_delivered() {
        val (mutableFlow, bubbles) = buildBubbleFlow()
        val received = AtomicReference<EmojiBubbleMessage?>(null)
        val mounted = AtomicInteger(0)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            DeferredBubbleOverlayHostUnderTest(
                deferralMs = 100L,
                bubbles = bubbles,
                onBubbleHostMounted = { mounted.incrementAndGet() },
                onBubbleReceived = { received.set(it) },
            )
        }

        
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()
        assertTrue(
            "Host must be mounted by t=200ms before emit",
            mounted.get() >= 1,
        )

        
        composeTestRule.mainClock.advanceTimeBy(50L)  
        emitterScope.launch {
            mutableFlow.emit(bubble(emoji = "after-mount", id = 250L))
        }

        
        composeTestRule.mainClock.advanceTimeBy(50L)  
        composeTestRule.waitForIdle()

        val bubble = received.get()
        assertEquals(
            "Bubble emitted after host mounts must be delivered",
            "after-mount",
            bubble?.emoji,
        )
    }
}
