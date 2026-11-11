package com.difft.android.call

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.difft.android.base.call.CallEncryptOutcome
import com.difft.android.base.call.CallEncryptResult
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.Data
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.session.CallSessionLauncher
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.test.rules.GlobalStaticMockRule
import difft.android.messageserialization.For
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections











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
        
        
        every { CallWaitDialogUtil.show(any()) } answers {
            ComposeDialogManager.showWait(firstArg(), layoutId = android.R.layout.simple_list_item_1)
        }
        every { launcher.launch(any(), any(), any(), any()) } returnsMany listOf(true, false)
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

    @Test
    fun `a createCallMsg flip in a warm process reaches the next start's envelope and launch`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val configs = mutableListOf(configWith(createCallMsg = false))
        val envelopeFlags = Collections.synchronizedList(mutableListOf<Boolean>())
        val launchedConfigs = Collections.synchronizedList(mutableListOf<CallConfig?>())
        every { launcher.launch(any(), any(), any(), any()) } answers {
            launchedConfigs += arg<CallConfig?>(3)
            true
        }
        val start = startController(
            globalConfigs = { configs.last() },
            onEnvelope = { envelopeFlags += it },
        )

        start.startCall(activity, For.Account("peer-1"), "peer") { ok, _ -> results += ok }
        idleUntil { results.size == 1 }
        configs += configWith(createCallMsg = true)
        start.startCall(activity, For.Account("peer-1"), "peer") { ok, _ -> results += ok }
        idleUntil { results.size == 2 }

        assertEquals(listOf(false, true), envelopeFlags.toList())
        assertEquals(listOf(false, true), launchedConfigs.map { it?.createCallMsg })
    }

    @Test
    fun `one start reads the config once and shares that snapshot between envelope and launch`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val a = configWith(createCallMsg = true)
        val b = configWith(createCallMsg = false)
        val served = mutableListOf(a, b, b, b)
        val envelopeFlags = Collections.synchronizedList(mutableListOf<Boolean>())
        var launched: CallConfig? = null
        every { launcher.launch(any(), any(), any(), any()) } answers {
            launched = arg(3)
            true
        }
        val globalConfigs = mockk<GlobalConfigsManager>(relaxed = true) {
            every { getNewGlobalConfigs() } answers { served.removeAt(0) }
        }
        val start = startController(globalConfigsManager = globalConfigs, onEnvelope = { envelopeFlags += it })

        start.startCall(activity, For.Account("peer-1"), "peer") { ok, _ -> results += ok }
        idleUntil { results.size == 1 }

        verify(exactly = 1) { globalConfigs.getNewGlobalConfigs() }
        assertEquals(listOf(true), envelopeFlags.toList())
        assertSame(a.data?.call, launched)
    }

    private fun configWith(createCallMsg: Boolean) =
        NewGlobalConfig(data = Data(call = CallConfig(createCallMsg = createCallMsg)))

    private fun startController(
        globalConfigs: () -> NewGlobalConfig? = { null },
        globalConfigsManager: GlobalConfigsManager =
            mockk(relaxed = true) { every { getNewGlobalConfigs() } answers { globalConfigs() } },
        onEnvelope: (Boolean) -> Unit = {},
    ) = LChatToCallControllerImpl(
        httpClient = mockk(relaxed = true),
        callMessageCreator = mockk {
            coEvery {
                createCallMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } answers {
                onEnvelope(arg(11))
                CallEncryptOutcome.Success(CallEncryptResult())
            }
        },
        messageEncryptor = mockk(relaxed = true),
        dbRoomStore = mockk(relaxed = true),
        globalConfigsManager = globalConfigsManager,
        contactorCacheManager = mockk(relaxed = true),
        callMessageHandler = mockk(relaxed = true),
        sessionLauncher = { launcher },
    )

    
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
