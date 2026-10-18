package com.difft.android.call

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.difft.android.base.call.CallEncryptOutcome
import com.difft.android.base.call.CallEncryptResult
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.session.CallSessionLauncher
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.test.rules.GlobalStaticMockRule
import difft.android.messageserialization.For
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The outgoing START entry point ([LChatToCallControllerImpl.startCall]) on a double tap: both
 * starts share the screen's wait dialog; the launcher accepts the first and refuses the second,
 * and the refused start releases only its own share, so the first start's dialog stays up for its
 * call page. Real wait-dialog slot on a Robolectric Activity; the start runs on the real `appScope`
 * and main Looper, so the test idles the Looper until both starts report back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = CallJoinStarterTest.TestApplication::class, sdk = [33])
class LChatToCallControllerStartTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private val launcher = mockk<CallSessionLauncher>()
    private val results = Collections.synchronizedList(mutableListOf<Boolean>())

    @Before
    fun setUp() {
        mockkObject(LCallEngine, LCallManager, CallWaitDialogUtil)
        every { LCallEngine.isNetworkAvailable() } returns true
        coEvery { LCallManager.checkQuicFeatureGrayStatus() } just runs
        // The real slot with a static layout: the default spinner is an infinite animation, and
        // idling the Robolectric main looper runs its frames without end, filling the worker heap.
        every { CallWaitDialogUtil.show(any()) } answers {
            ComposeDialogManager.showWait(firstArg(), layoutId = android.R.layout.simple_list_item_1)
        }
        every { launcher.launch(any(), any(), any()) } returnsMany listOf(true, false)
    }

    @After
    fun tearDown() {
        ComposeDialogManager.dismissWait()
        unmockkAll()
    }

    @Test
    fun `a refused second start leaves the first start's wait dialog up`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val baseline = contentRoot.childCount
        val start = startController()

        start.startCall(activity, For.Account("peer-1"), "peer") { ok, _ -> results += ok }
        start.startCall(activity, For.Account("peer-1"), "peer") { ok, _ -> results += ok }
        idleUntil { results.size == 2 }

        assertEquals(listOf(false, true), results.sorted())
        assertEquals("the first start's dialog is still up", baseline + 1, contentRoot.childCount)
        controller.destroy()
    }

    private fun startController() = LChatToCallControllerImpl(
        httpClient = mockk(relaxed = true),
        callMessageCreator = mockk {
            coEvery {
                createCallMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns CallEncryptOutcome.Success(CallEncryptResult())
        },
        messageEncryptor = mockk(relaxed = true),
        dbRoomStore = mockk(relaxed = true),
        globalConfigsManager = mockk(relaxed = true) { every { getNewGlobalConfigs() } returns null },
        contactorCacheManager = mockk(relaxed = true),
        callMessageHandler = mockk(relaxed = true),
        sessionLauncher = { launcher },
    )

    /** Runs main-Looper work until [done]; the starts hop through the real IO dispatcher. */
    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "starts did not report back" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
