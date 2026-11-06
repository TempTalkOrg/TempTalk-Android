package com.difft.android.chat.widget

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class VoiceRecorderViewHoldTest {

    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var recorder: VoiceRecorderView
    private lateinit var anchor: View
    private val states = mutableListOf<RecordingState>()
    private var sessionEnded = 0

    @Before
    fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        recorder = VoiceRecorderView(activity)
        anchor = View(activity)
        activity.setContentView(recorder)
        recorder.recordingCallback = { states += it }
        recorder.onHoldSessionEnded = { sessionEnded++ }
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun `denied audio focus reports a failed start`() {
        shadowOf(audioManager()).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertFalse(recorder.beginHold(anchor))

        assertEquals(1, sessionEnded)
        assertEquals(RecordingState.RecordFailed(RecordingState.Reason.AUDIO_FOCUS_DENIED), states.last())
    }

    @Test
    fun `losing audio focus mid-hold ends the session once`() {
        assertTrue(recorder.beginHold(anchor))

        loseAudioFocus()
        recorder.endHold()

        assertEquals(1, sessionEnded)
    }

    @Test
    fun `a new hold is rejected until the previous session finishes finalizing`() {
        assertTrue(recorder.beginHold(anchor))
        loseAudioFocus()

        assertFalse(recorder.beginHold(anchor))
        assertEquals(1, states.count { it is RecordingState.Started })

        val deadline = System.currentTimeMillis() + FINALIZE_WAIT_MS
        while (states.none { it is RecordingState.Cancelled } && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
        }
        assertTrue(states.any { it is RecordingState.Cancelled })

        // The finalizer job completes on its IO thread just after delivering the callback.
        var restarted = false
        while (!restarted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            restarted = recorder.beginHold(anchor)
        }
        assertTrue(restarted)
        recorder.cancelHold()
    }

    @Test
    fun `only the first bar placement skips a frame, later anchor moves do not freeze drawing`() {
        val activity = controller.get()
        val root = FrameLayout(activity)
        val bar = VoiceRecorderView(activity)
        val field = View(activity)
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(field, FrameLayout.LayoutParams(300, 100, Gravity.BOTTOM).apply { leftMargin = 40; bottomMargin = 20 })
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(bar.beginHold(field))
        assertTrue("first placement cancels the stale frame", bar.viewTreeObserver.dispatchOnPreDraw())

        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        field.translationY = -200f

        assertFalse("a moving anchor must not cancel the window draw", bar.viewTreeObserver.dispatchOnPreDraw())
        bar.cancelHold()
    }

    private fun loseAudioFocus() {
        shadowOf(audioManager()).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
    }

    private fun audioManager(): AudioManager =
        controller.get().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private companion object {
        const val FINALIZE_WAIT_MS = 10_000L
    }
}
