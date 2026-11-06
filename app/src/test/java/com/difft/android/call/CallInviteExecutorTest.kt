package com.difft.android.call

import com.difft.android.base.call.CallEncryptOutcome
import com.difft.android.base.call.CallEncryptResult
import com.difft.android.base.call.CallType
import com.difft.android.base.call.InviteCallResponseData
import com.difft.android.base.user.CallConfig
import com.difft.android.call.handler.InviteRequestState
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.network.BaseResponse
import com.difft.android.test.rules.GlobalStaticMockRule
import com.difft.android.websocket.api.util.CallMessageCreator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * [CallInviteExecutor.inviteCall]: each invite reads one [CallConfig] snapshot at its entry and
 * uses it for both the INVITE envelope and the success callback's local-record decision, so a
 * server flip of `createCallMsg` in a warm process reaches the next invite, and a flip while the
 * invite request is in flight does not split the current one (#1395).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = CallJoinStarterTest.TestApplication::class, sdk = [33])
class CallInviteExecutorTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private var liveConfig = CallConfig(createCallMsg = false)
    private var onRequest: () -> Unit = {}
    private val envelopeFlags = Collections.synchronizedList(mutableListOf<Boolean>())

    private val callService = mockk<LCallHttpService> {
        coEvery { inviteCall(any()) } answers {
            onRequest()
            BaseResponse(ver = 1, status = 0, reason = null, data = InviteCallResponseData(null, null, null))
        }
    }
    private val callMessageCreator = mockk<CallMessageCreator> {
        coEvery {
            createCallMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            envelopeFlags += arg<Boolean>(11)
            CallEncryptOutcome.Success(CallEncryptResult())
        }
    }

    private val executor = CallInviteExecutor(
        callService = callService,
        callMessageCreator = callMessageCreator,
        callConfigProvider = { liveConfig },
        mySelfId = SELF,
        contactorCacheManager = mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        mockkObject(LCallManager)
        every {
            LCallManager.sendOrLocalCallTextMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } just runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a createCallMsg flip in a warm process reaches the next invite's envelope and record`() = runBlocking {
        assertEquals(InviteRequestState.SUCCESS, invite())
        verifyRecorded(createCallMsg = false)

        liveConfig = CallConfig(createCallMsg = true)
        assertEquals(InviteRequestState.SUCCESS, invite())

        assertEquals(listOf(false, true), envelopeFlags.toList())
        verifyRecorded(createCallMsg = true)
    }

    @Test
    fun `a flip while the invite request is in flight keeps the entry snapshot`() = runBlocking {
        liveConfig = CallConfig(createCallMsg = true)
        onRequest = { liveConfig = CallConfig(createCallMsg = false) }

        assertEquals(InviteRequestState.SUCCESS, invite())

        assertEquals(listOf(true), envelopeFlags.toList())
        verifyRecorded(createCallMsg = true)
        verify(exactly = 0) {
            LCallManager.sendOrLocalCallTextMessage(any(), any(), any(), any(), any(), any(), any(), any(), false, any())
        }
    }

    private suspend fun invite() = executor.inviteCall(
        roomId = "room-1",
        roomName = "room",
        callType = CallType.GROUP.type,
        mKey = null,
        inviteMembers = arrayListOf(INVITEE),
        conversationId = "group-1",
    )

    /** The success callback records on `appScope`, so the check waits for it. */
    private fun verifyRecorded(createCallMsg: Boolean) {
        verify(timeout = TIMEOUT_MS) {
            LCallManager.sendOrLocalCallTextMessage(any(), any(), any(), any(), any(), any(), any(), any(), createCallMsg, any())
        }
    }

    private companion object {
        const val SELF = "+10000000001"
        const val INVITEE = "+10000000002"
        const val TIMEOUT_MS = 5_000L
    }
}
