package com.difft.android.home

import android.app.Application
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeTabPillIdleTest {
    @After
    fun drainSnapshotNotifications() {
        
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    @Test
    fun `initial and unchanged selection leave no ongoing frame work`() = runTest {
        val harness = MotionHarness(this)
        try {
            harness.assertIdleForThreeSeconds()
            val sameTarget = harness.animateSelection(HomeTab.CHATS)
            harness.settle()
            assertTrue(sameTarget.isCompleted)
            assertEquals(0f, harness.motion.index, 0f)
            harness.assertIdleForThreeSeconds()
        } finally {
            harness.close()
        }
    }

    @Test
    fun `external selection animates position and independent pressure then becomes idle`() = runTest {
        val harness = MotionHarness(this)
        try {
            val animation = harness.animateSelection(HomeTab.ME)
            harness.advanceFrames(80)
            assertTrue("the test must drive the real production spring", harness.motion.index > 0f)
            assertTrue("the real press spring must run during selection", harness.motion.pressState.value > 0f)
            assertTrue("independent growth springs must actually run", harness.motion.scaleAxis > 1f)
            assertTrue("snapshot observation must detect the animation", harness.snapshotUpdates > 0)
            harness.settle()
            assertTrue(animation.isCompleted)
            assertEquals(HomeTab.ME.toFloat(), harness.motion.index, 0f)
            harness.assertIdleForThreeSeconds()
        } finally {
            harness.close()
        }
    }

    @Test
    fun `fractional drag release selects once settles and stops frame work`() = runTest {
        val harness = MotionHarness(this)
        try {
            harness.motion.startDrag()
            harness.motion.drag(delta = 75f, reverse = false)
            assertEquals(1.25f, harness.motion.targetIndex, 0f)
            harness.advanceFrames(80)
            assertTrue(harness.motion.index > 0f && harness.motion.index < 1.25f)
            harness.assertVelocityDeformation(positive = true)
            assertEquals(75f, harness.motion.panelDisplacement, 0f)
            harness.motion.release()
            harness.advanceFrames(80)
            assertTrue("the actual return spring must animate the displaced panel", harness.motion.panelDisplacement > 0f)
            harness.settle()
            assertEquals(listOf(HomeTab.CONTACTS), harness.selections)
            assertEquals(HomeTab.CONTACTS.toFloat(), harness.motion.index, 0f)
            assertFalse(harness.motion.dragging)
            harness.assertIdleForThreeSeconds()
        } finally {
            harness.close()
        }
    }

    @Test
    fun `negative drag velocity deforms the production pill in the opposite direction and settles`() = runTest {
        val harness = MotionHarness(this, initial = HomeTab.ME)
        try {
            harness.motion.startDrag()
            harness.motion.drag(delta = -75f, reverse = false)
            assertEquals(0.75f, harness.motion.targetIndex, 0f)
            harness.advanceFrames(80)
            assertTrue(harness.motion.index > 0.75f && harness.motion.index < 2f)
            harness.assertVelocityDeformation(positive = false)
            harness.motion.release()
            harness.settle()
            assertEquals(listOf(HomeTab.CONTACTS), harness.selections)
            harness.assertIdleForThreeSeconds()
        } finally { harness.close() }
    }

    @Test
    fun `RTL physical panel drag retains its direction and cancellation settles without idle work`() = runTest {
        val harness = MotionHarness(this)
        try {
            harness.animateSelection(HomeTab.ME)
            harness.settle()
            harness.motion.startDrag()
            harness.motion.drag(delta = 60f, reverse = true)
            assertEquals(1f, harness.motion.targetIndex, 0f)
            assertEquals(60f, harness.motion.panelDisplacement, 0f)
            harness.motion.cancelDrag()
            harness.settle()
            assertEquals(2f, harness.motion.index, 0f)
            assertTrue(harness.selections.isEmpty())
            harness.assertIdleForThreeSeconds()
        } finally {
            harness.close()
        }
    }

    @Test
    fun `cancelled drag returns to latest controlled selection and stops frame work`() = runTest {
        val harness = MotionHarness(this)
        try {
            harness.motion.startDrag()
            harness.motion.drag(delta = 110f, reverse = false)
            harness.selected.value = HomeTab.CONTACTS
            harness.motion.cancelDrag()
            harness.settle()
            assertTrue(harness.selections.isEmpty())
            assertEquals(HomeTab.CONTACTS.toFloat(), harness.motion.index, 0f)
            assertFalse(harness.motion.dragging)
            assertFalse(harness.motion.pressed)
            harness.assertIdleForThreeSeconds()
        } finally {
            harness.close()
        }
    }

    @Test
    fun `held press releases visibly within a quarter second then becomes exactly idle`() = runTest {
        val harness = MotionHarness(this)
        try {
            assertEquals(0f, harness.motion.pressState.value, 0f)
            assertEquals(1f, harness.motion.scaleAxis, 0f)
            harness.motion.pointerDown()
            harness.advanceFrames(800)
            assertEquals(78f / 56f, harness.motion.scaleAxis, 0.001f)
            assertEquals(78f / 56f, harness.motion.scaleCross, 0.001f)
            harness.motion.pointerUp()
            harness.advanceFrames(272)
            assertTrue(harness.motion.pressState.value < 0.01f)
            assertEquals(1f, harness.motion.scaleAxis, 0.05f)
            assertEquals(1f, harness.motion.scaleCross, 0.05f)
            harness.settle()
            harness.assertIdleForThreeSeconds()
        } finally { harness.close() }
    }

    @Test
    fun `fast drag release and repress cancel old release waits without retaining a pressed state`() = runTest {
        val harness = MotionHarness(this)
        try {
            harness.motion.pointerDown()
            harness.motion.startDrag()
            harness.motion.drag(60f, reverse = false)
            harness.advanceFrames(32)
            harness.motion.drag(60f, reverse = false)
            harness.advanceFrames(32)
            harness.motion.release()
            harness.advanceFrames(32)
            harness.motion.pointerDown()
            harness.advanceFrames(160)
            assertTrue("an old release must not shrink the new press", harness.motion.pressState.value > 0.9f)
            harness.motion.pointerUp()
            harness.settle()
            assertEquals(listOf(HomeTab.ME), harness.selections)
            harness.assertIdleForThreeSeconds()
        } finally { harness.close() }
    }

    @Test
    fun `rapid external selections replace release waits and finish at the latest controlled tab`() = runTest {
        val harness = MotionHarness(this)
        try {
            harness.animateSelection(HomeTab.CONTACTS)
            harness.advanceFrames(32)
            harness.animateSelection(HomeTab.ME)
            harness.advanceFrames(32)
            harness.animateSelection(HomeTab.CHATS)
            harness.settle()
            assertEquals(0f, harness.motion.index, 0f)
            assertTrue(harness.selections.isEmpty())
            harness.assertIdleForThreeSeconds()
        } finally { harness.close() }
    }

    @Test
    fun `release before the first nonzero velocity frame retargets the pending speed spring to rest`() = runTest {
        val harness = MotionHarness(this)
        try {
            val speed = ReflectionHelpers.getField<Animatable<Float, AnimationVector1D>>(harness.motion, "speed")
            harness.motion.pointerDown()
            harness.motion.startDrag()
            harness.motion.drag(120f, reverse = false)
            var pending = false
            repeat(8) {
                if (!pending) {
                    harness.advanceFrames(16)
                    pending = speed.targetValue != 0f && speed.value == 0f
                }
            }
            assertTrue("fixture must reach the real pending-velocity race before its first frame", pending)
            harness.motion.release()
            
            harness.runPendingTasks()
            assertEquals(0f, speed.targetValue, 0f)
            harness.settle()
            harness.assertIdleForThreeSeconds()
        } finally { harness.close() }
    }

    @Test
    fun `release supersedes a queued nonzero velocity update before dispatch and becomes idle`() = runTest {
        val harness = MotionHarness(this)
        try {
            val speed = ReflectionHelpers.getField<Animatable<Float, AnimationVector1D>>(harness.motion, "speed")
            val tracker = ReflectionHelpers.getField<VelocityTracker>(harness.motion, "tracker")
            harness.motion.startDrag()
            harness.motion.drag(120f, reverse = false)
            harness.advanceFrames(32)
            harness.advanceFrameBeforeDispatch()
            assertTrue("the position frame must have advanced before queuing its speed update", harness.motion.index > 0f)
            assertTrue("the real three-sample tracker must have queued a nonzero velocity", tracker.calculateVelocity().x > 0f)
            assertEquals("the queued update must not yet be observable as a speed target", 0f, speed.targetValue, 0f)
            assertEquals(0f, speed.value, 0f)
            
            harness.motion.release()
            harness.runPendingTasks()
            assertEquals(0f, speed.targetValue, 0f)
            harness.settle()
            harness.assertIdleForThreeSeconds()
        } finally { harness.close() }
    }

    @Test
    fun `dispose cancels both the active speed spring and a queued replacement`() = runTest {
        val harness = MotionHarness(this)
        try {
            harness.motion.startDrag()
            harness.motion.drag(120f, reverse = false)
            harness.advanceFrames(80)
            harness.assertVelocityDeformation(positive = true)
            harness.advanceFrameBeforeDispatch()
            harness.motion.dispose()
            harness.runPendingTasks()
            harness.assertNoFrameWorkForThreeSeconds()
        } finally { harness.close() }
    }

    private class MotionHarness(private val test: TestScope, initial: Int = HomeTab.CHATS) : AutoCloseable {
        private val clock = BroadcastFrameClock()
        private val motionJob = SupervisorJob(test.coroutineContext[Job])
        private val scope = CoroutineScope(test.coroutineContext + motionJob + clock)
        val selected = mutableStateOf(initial)
        val selections = mutableListOf<Int>()
        private val onSelect: State<(Int) -> Unit> = mutableStateOf({ index: Int ->
            selections += index
            selected.value = index
        })
        val motion = PillMotion(initial, scope, selected, onSelect, nowMillis = { test.testScheduler.currentTime }).apply { slotExtent = 60f }
        var snapshotUpdates = 0
            private set
        private val observer = Snapshot.registerApplyObserver { changes, _ -> snapshotUpdates += changes.size }

        fun animateSelection(target: Int): Job {
            selected.value = target
            return scope.launch { motion.animateSelection(target) }
        }

        fun advanceFrames(milliseconds: Long) {
            val end = test.testScheduler.currentTime + milliseconds
            test.runCurrent()
            while (test.testScheduler.currentTime < end) {
                test.advanceTimeBy(minOf(16L, end - test.testScheduler.currentTime))
                test.runCurrent()
                clock.sendFrame(test.testScheduler.currentTime * 1_000_000L)
                test.runCurrent()
                Snapshot.sendApplyNotifications()
            }
        }

        fun advanceFrameBeforeDispatch() {
            test.advanceTimeBy(16)
            test.runCurrent()
            
            clock.sendFrame(test.testScheduler.currentTime * 1_000_000L)
        }

        fun runPendingTasks() = test.runCurrent()

        fun assertVelocityDeformation(positive: Boolean) {
            val velocity = motion.velocity
            assertTrue("animated-value velocity must update the actual deformation state",
                if (positive) velocity > 0f else velocity < 0f)
            val moving = HomeTabLensMath.scale(velocity, motion.scaleAxis, motion.scaleCross)
            val stationary = HomeTabLensMath.scale(0f, motion.scaleAxis, motion.scaleCross)
            assertTrue("signed speed must deform the real longitudinal scale",
                if (positive) moving.axis > stationary.axis else moving.axis < stationary.axis)
            assertTrue("signed speed must deform the real cross scale in the opposite direction",
                if (positive) moving.cross < stationary.cross else moving.cross > stationary.cross)
        }

        fun settle() {
            test.runCurrent()
            repeat(125) {
                if (!clock.hasAwaiters && motionJob.children.none { child -> child.isActive }) return
                advanceFrames(16)
            }
            error("Production pill motion did not settle within two seconds")
        }

        fun assertIdleForThreeSeconds() {
            test.runCurrent()
            Snapshot.sendApplyNotifications()
            assertFalse("settled motion must not have frame awaiters", clock.hasAwaiters)
            assertTrue("settled motion must not retain an active animation job", motionJob.children.none { it.isActive })
            assertEquals(0f, motion.velocity, 0f)
            assertEquals(0f, motion.panelDisplacement, 0f)
            assertEquals(0f, motion.pressState.value, 0f)
            assertEquals(1f, motion.scaleAxis, 0f)
            assertEquals(1f, motion.scaleCross, 0f)
            assertNoFrameWorkForThreeSeconds()
        }

        fun assertNoFrameWorkForThreeSeconds() {
            test.runCurrent()
            Snapshot.sendApplyNotifications()
            assertFalse("motion must not retain frame awaiters", clock.hasAwaiters)
            assertTrue("motion must not retain animation jobs", motionJob.children.none { it.isActive })
            val before = snapshotUpdates
            advanceFrames(3_000)
            assertFalse("idle motion must not enqueue another frame", clock.hasAwaiters)
            assertTrue(motionJob.children.none { it.isActive })
            assertEquals("idle motion must not publish more snapshot changes", before, snapshotUpdates)
        }

        override fun close() {
            observer.dispose()
            scope.cancel()
            clock.cancel()
        }
    }
}
