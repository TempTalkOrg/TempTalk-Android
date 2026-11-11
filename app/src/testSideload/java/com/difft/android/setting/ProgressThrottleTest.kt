package com.difft.android.setting

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue





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
        
        assertFalse(throttle.shouldEmit(3, 100L))
    }

    @Test
    fun stepThresholdEmits() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        
        assertTrue(throttle.shouldEmit(5, 100L))
    }

    @Test
    fun intervalThresholdEmits() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        
        assertTrue(throttle.shouldEmit(2, 600L))
    }

    @Test
    fun hundredPercentAlwaysEmits() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        
        assertFalse(throttle.shouldEmit(1, 50L))
        
        assertTrue(throttle.shouldEmit(100, 60L))
    }

    @Test
    fun thresholdAccumulatesFromLastEmitNotLastCall() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldEmit(0, 0L))
        
        assertFalse(throttle.shouldEmit(2, 50L))
        assertFalse(throttle.shouldEmit(4, 100L))
        
        assertTrue(throttle.shouldEmit(5, 150L))
    }
}
