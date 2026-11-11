package com.difft.android.login

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.difft.android.test.TestDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue







@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResendCountdownTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun label() = TextView(context)

    private fun expectedText(seconds: Int?) =
        context.getString(R.string.login_email_code_resend, if (seconds == null) "" else "(${seconds}s)")

    @Test
    fun `the link re-enables exactly at the deadline`() = runTest {
        val label = label()
        
        ResendCountdown(label, backgroundScope) { testScheduler.currentTime }.start()
        runCurrent()

        assertEquals(expectedText(60), label.text.toString())
        assertFalse(label.isEnabled)

        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(expectedText(1), label.text.toString())
        assertFalse(label.isEnabled, "the link stays disabled until the deadline")

        advanceTimeBy(1)
        runCurrent()
        assertTrue(label.isEnabled, "and re-enables at it")
        assertEquals(expectedText(null), label.text.toString())
    }

    @Test
    fun `a tick resuming off the second boundary realigns so the last second is whole`() = runTest {
        val label = label()
        
        
        var sleptMillis = 0L
        ResendCountdown(label, backgroundScope) { testScheduler.currentTime + sleptMillis }.start()
        runCurrent()

        sleptMillis = 500
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(expectedText(59), label.text.toString(), "the wake catches up rather than resuming where it left off")

        
        
        advanceTimeBy(57_500)
        runCurrent()
        assertEquals(expectedText(1), label.text.toString())

        advanceTimeBy(999)
        runCurrent()
        assertFalse(label.isEnabled)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(label.isEnabled)
    }
}
