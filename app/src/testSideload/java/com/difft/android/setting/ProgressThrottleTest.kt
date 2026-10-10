package com.difft.android.setting

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for [ProgressThrottle] (5% step / 500ms interval gate). nowMs is passed
 * explicitly, so no clock mocking is needed.
 */
class ProgressThrottleTest {

    @Test
    fun firstCallAlwaysEmits() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
    }

    @Test
    fun smallStepWithinShortIntervalSuppressed() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        // step 3% < 5% and 100ms < 500ms → suppressed
        assertFalse(throttle.shouldEmit(3, 100L))
    }

    @Test
    fun stepThresholdEmits() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        // step 5% >= 5% → emit even though 100ms < 500ms
        assertTrue(throttle.shouldEmit(5, 100L))
    }

    @Test
    fun intervalThresholdEmits() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        // 600ms >= 500ms → emit even though step 2% < 5%
        assertTrue(throttle.shouldEmit(2, 600L))
    }

    @Test
    fun hundredPercentAlwaysEmits() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        // suppressed intermediate update
        assertFalse(throttle.shouldEmit(1, 50L))
        // final progress must emit despite small step + short interval
        assertTrue(throttle.shouldEmit(100, 60L))
    }

    @Test
    fun thresholdAccumulatesFromLastEmitNotLastCall() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        // three suppressed 2% calls; step is measured from the last EMITTED value (0)
        assertFalse(throttle.shouldEmit(2, 50L))
        assertFalse(throttle.shouldEmit(4, 100L))
        // 5% - 0% (last emit) >= 5% → emit
        assertTrue(throttle.shouldEmit(5, 150L))
    }
}
