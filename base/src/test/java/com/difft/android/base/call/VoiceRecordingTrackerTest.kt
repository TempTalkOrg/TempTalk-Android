package com.difft.android.base.call

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue















class VoiceRecordingTrackerTest {

    @Before
    fun setUp() {
        
        VoiceRecordingTracker.setRecording(false, "test_reset")
    }

    @After
    fun tearDown() {
        
        VoiceRecordingTracker.setRecording(false, "test_reset")
    }

    








    @Test
    fun `initial value is false`() {
        assertEquals(false, VoiceRecordingTracker.isRecording)
    }

    


    @Test
    fun `setRecording true sets isRecording to true`() {
        VoiceRecordingTracker.setRecording(true, "start")

        assertEquals(true, VoiceRecordingTracker.isRecording)
    }

    



    @Test
    fun `setRecording false after true sets isRecording to false`() {
        VoiceRecordingTracker.setRecording(true, "start")
        VoiceRecordingTracker.setRecording(false, "stop")

        assertEquals(false, VoiceRecordingTracker.isRecording)
    }

    








    @Test
    fun `setRecording idempotent same value`() {
        VoiceRecordingTracker.setRecording(true, "first")
        VoiceRecordingTracker.setRecording(true, "second")

        assertTrue(
            VoiceRecordingTracker.isRecording,
            "Expected isRecording to remain true after two consecutive setRecording(true) calls"
        )
    }

    



    @Test
    fun `tracker reset on detach path`() {
        VoiceRecordingTracker.setRecording(true, "start")
        VoiceRecordingTracker.setRecording(false, "detach")

        assertEquals(false, VoiceRecordingTracker.isRecording)
    }

    



    @Test
    fun `tracker reset on abort path`() {
        VoiceRecordingTracker.setRecording(true, "start")
        VoiceRecordingTracker.setRecording(false, "abort")

        assertEquals(false, VoiceRecordingTracker.isRecording)
    }
}
