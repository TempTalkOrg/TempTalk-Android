package com.difft.android.call

import com.difft.android.base.call.CallType
import com.difft.android.call.data.CallStatus
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.ui.CallVmTestHarness
import com.difft.android.network.UrlManager
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LCallViewModel]'s two additive E2EE accessors. Covers T5-15..T5-17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class LCallViewModelE2eeAccessorsTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildViewModel(conversationId: String? = "conv-456", urlManager: UrlManager = mockk(relaxed = true)): LCallViewModel {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(
            CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type, conversationId = conversationId,
        )
        CallVmTestHarness.stubCallStatus(CallStatus.CALLING, CallType.ONE_ON_ONE.type)
        return CallVmTestHarness.buildViewModel(callIntent, urlManager = urlManager)
    }

    // ---------------------------------------------------------------------------------
    // T5-15 — participants contains one RemoteParticipant → its identity is returned.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T5-15 - getOneOnOnePeerId returns the joined remote participant identity`() {
        val vm = buildViewModel(conversationId = "conv-456")
        val remote = mockk<RemoteParticipant>(relaxed = true)
        every { remote.identity } returns Participant.Identity("u123")
        vm.participantManager.setParticipants(listOf(remote))

        assertEquals("u123", vm.getOneOnOnePeerId())
    }

    // ---------------------------------------------------------------------------------
    // T5-16 — participants empty → falls back to conversationId (pre-join).
    // ---------------------------------------------------------------------------------
    @Test
    fun `T5-16 - getOneOnOnePeerId falls back to conversationId before join`() {
        val vm = buildViewModel(conversationId = "u456")

        assertEquals("u456", vm.getOneOnOnePeerId())
    }

    // ---------------------------------------------------------------------------------
    // T5-17 — e2eeLearnMoreUrl is pure delegation to UrlManager.e2eeLearnMoreUrl.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T5-17 - e2eeLearnMoreUrl delegates to UrlManager unchanged`() {
        val urlManager: UrlManager = mockk(relaxed = true)
        every { urlManager.e2eeLearnMoreUrl } returns "https://yelling.pro/security"
        val vm = buildViewModel(urlManager = urlManager)

        assertEquals("https://yelling.pro/security", vm.e2eeLearnMoreUrl)
    }
}
