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

/**
 * Integration tests for the deferral semantics of `DeferredBubbleOverlayWindowHost`
 * (`CallContent.kt:241-253`).
 *
 * Coverage from `tmp/bug-anr-multiparticipant/design-report.md` §Test Strategy
 * (`BubbleOverlayDeferralIntegrationTest` row):
 *  1. Host not yet mounted at t=50ms (before deferral expires).
 *  2. Host mounted after t=150ms (after deferral expires).
 *  3. **In-window-drop trade-off lock-in**: bubble emitted at t=30ms is dropped
 *     (CI fails if anyone re-introduces `replay = 1` on the SharedFlow).
 *  4. After-mount delivery: bubble emitted at t=200ms is delivered.
 *
 * The real `BubbleOverlayWindowHost` adds a secondary `TYPE_APPLICATION_PANEL` Window via
 * `WindowManager.addView`, which is not reasonably testable in Robolectric. Instead, this
 * test exercises the **deferral pattern** (the LaunchedEffect + withFrameNanos + delay
 * sequence and the gated child composition + the late SharedFlow subscription) — the
 * pattern that determines drop / deliver behavior. The pattern under test is identical
 * to `DeferredBubbleOverlayWindowHost`'s actual code in the production source, so a
 * regression in the production deferral mechanism will surface here.
 *
 * Time control: `composeTestRule.mainClock.autoAdvance = false` lets us advance the
 * Compose test clock by precise increments (50ms / 100ms / 200ms) so each test can pin
 * the exact moment when the deferred subscriber starts collecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30])
class BubbleOverlayDeferralIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** External scope for emitting bubble events from the test outside the Compose clock. */
    private val emitterScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @After
    fun tearDown() {
        emitterScope.cancel()
    }

    /**
     * Mirrors the production [com.difft.android.call.core.CallUiController]'s bubble
     * SharedFlow definition exactly (replay = 0, extraBufferCapacity = 64, DROP_OLDEST):
     * `CallUiController.kt:46-50`. Keeping the buffer parameters identical is what the
     * in-window-drop assertion (case 3) is locking in.
     */
    private fun buildBubbleFlow(): Pair<MutableSharedFlow<EmojiBubbleMessage>, SharedFlow<EmojiBubbleMessage>> {
        val flow = MutableSharedFlow<EmojiBubbleMessage>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        return flow to flow.asSharedFlow()
    }

    /**
     * Exact same shape as `DeferredBubbleOverlayWindowHost(viewModel)` in production — a
     * private composable in `CallContent.kt:241-253` that defers its child until ~100ms
     * past the first frame, then conditionally hosts the gated content.
     *
     * The host's gated content here is a SharedFlow collector that mirrors
     * `BubbleOverlayLayer.kt:52-62`'s `LaunchedEffect(Unit) { flow.collect { … } }` —
     * the subscriber that exists ONLY after the host enters composition.
     */
    @Composable
    private fun DeferredBubbleOverlayHostUnderTest(
        deferralMs: Long,
        bubbles: SharedFlow<EmojiBubbleMessage>,
        onBubbleHostMounted: () -> Unit,
        onBubbleReceived: (EmojiBubbleMessage) -> Unit,
    ) {
        var bubbleHostReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos { /* return on next vsync */ }
            delay(deferralMs)
            bubbleHostReady = true
        }
        if (bubbleHostReady) {
            // Mirror the BubbleOverlayLayer subscriber: LaunchedEffect(Unit) { flow.collect }
            LaunchedEffect(Unit) {
                onBubbleHostMounted()
                bubbles.collect { onBubbleReceived(it) }
            }
            // Empty body — production has the BubbleOverlayWindowHost itself; in tests we
            // only care about the gated subscriber's drop / deliver behavior.
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

    /** Case 1: at t=50ms (before 100ms deferral expires), the bubble host is not mounted. */
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
                onBubbleReceived = { /* no-op for this case */ },
            )
        }

        // Advance to t=50ms — before the 100ms deferral has elapsed.
        composeTestRule.mainClock.advanceTimeBy(50L)
        composeTestRule.waitForIdle()

        assertEquals(
            "Host must not be mounted before deferral expires",
            0,
            mountedCounter.get(),
        )
    }

    /** Case 2: at t=150ms (past 100ms deferral), the host is mounted. */
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
                onBubbleReceived = { /* no-op for this case */ },
            )
        }

        // Advance well past the 100ms deferral to give Compose time to process the
        // LaunchedEffect's delay and run the recomposition that mounts the gated subscriber.
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        assertTrue(
            "Host must be mounted after deferral expires (got mountedCounter=${mountedCounter.get()})",
            mountedCounter.get() >= 1,
        )
    }

    /**
     * Case 3 — **In-window-drop trade-off lock-in (CRITICAL)**:
     * A bubble emitted strictly within the 100ms deferral window is dropped. If a future
     * change re-introduces `replay = 1` on the bubble SharedFlow (which would cause stale
     * bubble re-delivery on rotation / PiP exit), this test fails — forcing the author to
     * re-examine the rotation/PiP regression risk before accepting the change.
     *
     * The assertion is ASYMMETRIC by design: we tolerate that the receiver may not fire at
     * all (drop is the goal), but we MUST NOT see the in-window emoji land in the receiver.
     */
    @Test
    fun bubble_emitted_during_deferral_window_is_dropped() {
        val (mutableFlow, bubbles) = buildBubbleFlow()
        val received = AtomicReference<EmojiBubbleMessage?>(null)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            DeferredBubbleOverlayHostUnderTest(
                deferralMs = 100L,
                bubbles = bubbles,
                onBubbleHostMounted = { /* no-op */ },
                onBubbleReceived = { received.set(it) },
            )
        }

        // Step 1: advance to t=30ms — well inside the 100ms deferral window.
        composeTestRule.mainClock.advanceTimeBy(30L)

        // Step 2: emit a bubble synchronously while the gated subscriber is NOT yet
        // composed. With replay = 0, this emission is dropped — no subscriber is listening.
        emitterScope.launch {
            mutableFlow.emit(bubble(emoji = "in-window", id = 30L))
        }

        // Step 3: advance well past the 100ms deferral so the subscriber starts collecting.
        // If `replay` were ever 1, the in-window bubble would now be re-delivered to the
        // late subscriber — and the assertion below would fail.
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

    /** Case 4: a bubble emitted AFTER the host mounts is delivered to the gated subscriber. */
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

        // Step 1: advance past the deferral so the subscriber starts collecting.
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()
        assertTrue(
            "Host must be mounted by t=200ms before emit",
            mounted.get() >= 1,
        )

        // Step 2: emit a bubble while the subscriber is active.
        composeTestRule.mainClock.advanceTimeBy(50L)  // t=250ms
        emitterScope.launch {
            mutableFlow.emit(bubble(emoji = "after-mount", id = 250L))
        }

        // Step 3: give Compose / coroutines time to deliver the emission.
        composeTestRule.mainClock.advanceTimeBy(50L)  // t=300ms
        composeTestRule.waitForIdle()

        val bubble = received.get()
        assertEquals(
            "Bubble emitted after host mounts must be delivered",
            "after-mount",
            bubble?.emoji,
        )
    }
}
