package com.difft.android.call.handler

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.appScope
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.OnGoingCallStateManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exit semantics of [CallExitHandler] around the outbound hangup control message.
 *
 * An instant meeting has no owning conversation — `conversationId` is null by design all the way
 * down ([com.difft.android.websocket.api.util.CallMessageCreator] resolves instant recipients from
 * the participant uid list instead). A blanket "conversationId must be present" precondition
 * therefore swallowed end-for-all on instant meetings entirely, while 1v1 and group genuinely need
 * it to address the message. Both halves of that split are pinned here, together with the meeting
 * type the handler trusts and the point at which the uid list is sampled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallExitHandlerTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var viewModel: LCallViewModel
    private lateinit var rtm: RtmMessageHandler
    private lateinit var callToChatController: LCallToChatController
    private lateinit var callDataManager: CallDataManager

    /** Remote participants as reported at the moment [LCallViewModel.getCurrentCallUidList] runs. */
    private var remoteUids = listOf(REMOTE_UID)

    private var endCallCount = 0

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope

        viewModel = mockk(relaxed = true)
        rtm = mockk(relaxed = true)
        callToChatController = mockk(relaxed = true)
        // Real instance: a dependency-free holder, so the handler's own removeCallData runs for real.
        callDataManager = CallDataManager()
        remoteUids = listOf(REMOTE_UID)
        endCallCount = 0

        every { viewModel.rtm } returns rtm
        every { viewModel.callStatus } returns MutableStateFlow(CallStatus.CONNECTED)
        every { viewModel.getCurrentCallUidList() } answers { remoteUids }
        // Remote peers leave the room as soon as the end-call RTM lands, which is what empties the
        // uid list: sampling it after this point is the race the handler must not lose.
        every { rtm.sendEndCall(any()) } answers {
            remoteUids = emptyList()
            firstArg<(Boolean) -> Unit>().invoke(true)
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `instant meeting sends hangup even though it has no conversationId`() {
        val handler = buildHandler(callDataType = CallType.INSTANT.type, conversationId = null)

        handler.handleExit(exitParams(CallType.INSTANT.type), CallEndType.END)

        coVerify(exactly = 1) {
            callToChatController.hangUpCall(
                callerId = CALLER_ID,
                callRole = CallRole.CALLER,
                type = CallType.INSTANT.type,
                roomId = ROOM_ID,
                conversationId = null,
                callUidList = any(),
            )
        }
    }

    @Test
    fun `group call without conversationId ends locally instead of sending hangup`() {
        val handler = buildHandler(callDataType = CallType.GROUP.type, conversationId = null)

        handler.handleExit(exitParams(CallType.GROUP.type), CallEndType.END)

        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
        assertTrue("call must still be torn down locally", endCallCount > 0)
    }

    /**
     * `CallExitParams.callType` is built from the intent-seeded state holder, which stays empty
     * until [com.difft.android.call.session.CallTypeCoordinator] resolves the server type; the
     * resolved value lands on the `CallData` entry, so that is what decides instant-ness here.
     */
    @Test
    fun `resolved call data type wins over an unresolved params type`() {
        val handler = buildHandler(
            callDataType = CallType.INSTANT.type,
            conversationId = null,
            // Intent-seeded fallback still carries the pre-join type; it must not win.
            fallbackType = CallType.ONE_ON_ONE.type,
        )

        handler.handleExit(exitParams(callType = ""), CallEndType.END)

        coVerify(exactly = 1) {
            callToChatController.hangUpCall(
                callerId = any(),
                callRole = any(),
                type = CallType.INSTANT.type,
                roomId = ROOM_ID,
                conversationId = null,
                callUidList = any(),
            )
        }
    }

    @Test
    fun `participant uid list is sampled before the end-call rtm empties the room`() {
        val handler = buildHandler(callDataType = CallType.INSTANT.type, conversationId = null)

        handler.handleExit(exitParams(CallType.INSTANT.type), CallEndType.END)

        coVerify(exactly = 1) {
            callToChatController.hangUpCall(
                callerId = any(),
                callRole = any(),
                type = any(),
                roomId = any(),
                conversationId = any(),
                callUidList = listOf(REMOTE_UID),
            )
        }
    }

    @Test
    fun `leaving a multi-party call sends no control message`() {
        val handler = buildHandler(callDataType = CallType.INSTANT.type, conversationId = null)

        handler.handleExit(exitParams(CallType.INSTANT.type), CallEndType.LEAVE)

        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
        assertTrue("call must still be torn down locally", endCallCount > 0)
    }

    private fun buildHandler(
        callDataType: String?,
        conversationId: String?,
        fallbackType: String = callDataType.orEmpty(),
    ): CallExitHandler {
        callDataManager.addCallData(
            CallData(
                type = callDataType,
                version = 0,
                createdAt = 0L,
                roomId = ROOM_ID,
                caller = CallDataCaller(uid = CALLER_ID, did = 1),
                conversation = conversationId,
                encMeta = null,
            )
        )
        return CallExitHandler(
            viewModel = viewModel,
            callToChatController = callToChatController,
            onGoingCallStateManager = OnGoingCallStateManager(),
            callDataManager = callDataManager,
            callIntent = mockk<CallIntent>(relaxed = true),
            callRole = CallRole.CALLER,
            conversationId = conversationId,
            callType = fallbackType,
            onEndCall = { endCallCount++ },
        )
    }

    private fun exitParams(callType: String) = CallExitParams(
        roomId = ROOM_ID,
        callerId = CALLER_ID,
        callRole = CallRole.CALLER,
        callType = callType,
        conversationId = null,
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val CALLER_ID = "+10001"
        const val REMOTE_UID = "+10002"
    }
}
