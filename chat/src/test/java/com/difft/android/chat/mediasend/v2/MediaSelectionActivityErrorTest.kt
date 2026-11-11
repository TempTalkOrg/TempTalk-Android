package com.difft.android.chat.mediasend.v2

import android.app.Activity
import com.difft.android.chat.mediasend.MediaFailure
import com.difft.android.chat.mediasend.MediaFailureClassifier
import com.difft.android.chat.mediasend.MediaFailureReason
import com.difft.android.chat.mediasend.MediaSendFailureNotice
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.harness.LogCapture
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.every
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config













@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSelectionActivityErrorTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a send error keeps the screen and its result and is logged once`() {
        mockkObject(MediaSendFailureNotice)
        every { MediaSendFailureNotice.showThrown(any(), any(), any()) } just Runs
        val activity = Robolectric.buildActivity(MediaSelectionActivity::class.java).get()
        
        
        activity.setResult(Activity.RESULT_OK)
        val error = IllegalStateException("boom")

        val log = LogCapture()
        log.recording {
            activity.onSendError(error)
            awaitLine("the send failure line") { it.startsWith("[MediaSend] send failed") }
        }

        assertFalse(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val expected = MediaFailureClassifier.thrownLogLine(
            MediaFailure(
                position = MediaFailureClassifier.NO_POSITION,
                displayName = null,
                reason = MediaFailureReason.UNKNOWN,
                denialKind = null,
                cause = error,
            )
        )
        assertEquals(expected, log.messages.single { it.startsWith("[MediaSend] send failed") })
    }
}
