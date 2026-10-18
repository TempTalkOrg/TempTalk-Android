package com.difft.android.call

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.difft.android.base.call.CallActionType
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.CallFinishRequest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The call page's finish collector ([launchFinishRequestCollector]) on a real Activity lifecycle and
 * main Looper: it is not gated on STARTED (a backgrounded page, only CREATED, still finishes on a
 * remote end), and it runs one main-looper pass after the request is set, never inline in the
 * code that set it. Rows: T4-14, T4-15.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallFinishEffectIntegrationTest {

    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val request = MutableStateFlow<CallFinishRequest?>(null)
    private val tips = mutableListOf<String>()
    private val dismissals = mutableListOf<() -> Unit>()
    private var endCalls = 0

    @Test
    fun `T4-14 a page that is only CREATED shows the remote tip and ends after the tip is dismissed`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState)
        activity.launchFinishRequestCollector(request, showTip = { m, onDismiss -> tips += m; dismissals += onDismiss }) { endCalls++ }
        mainLooper.idle()

        request.value = CallFinishRequest.remote(CallActionType.HANGUP)
        mainLooper.idle()

        assertEquals(listOf(ResUtils.getString(R.string.call_callee_action_hangup)), tips)
        assertEquals("not before the tip is dismissed", 0, endCalls)

        dismissals.single().invoke()

        assertEquals(1, endCalls)
    }

    @Test
    fun `T4-15 a LOCAL request set on main ends the page one looper pass later, with no tip`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        activity.launchFinishRequestCollector(request, showTip = { m, onDismiss -> tips += m; dismissals += onDismiss }) { endCalls++ }
        mainLooper.idle()

        request.value = CallFinishRequest.LOCAL

        assertEquals("not run inline by the write", 0, endCalls)

        mainLooper.idle()

        assertEquals(1, endCalls)
        assertTrue(tips.isEmpty())
    }
}
