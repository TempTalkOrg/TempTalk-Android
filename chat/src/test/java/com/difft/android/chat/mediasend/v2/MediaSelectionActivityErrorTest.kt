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

/**
 * T72 — a throwable that escaped the whole send must not take the screen down with it.
 *
 * The previous implementation showed a generic toast, set a cancelled result and finished, which
 * discarded the typed caption and every selected item, and dropped the throwable without a log line.
 *
 * The activity is instantiated but not created: `onCreate` inflates a fragment container that pulls
 * in the whole DI graph, and none of that is what this contract is about. `onSendError` needs no
 * created state — it classifies, shows the notice and returns.
 *
 * Run: :chat:testDebugUnitTest
 */
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
        // Pre-set a distinguishable result: RESULT_CANCELED is also the shadow's default, so only a
        // value that would be overwritten can prove setResult was not called.
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
