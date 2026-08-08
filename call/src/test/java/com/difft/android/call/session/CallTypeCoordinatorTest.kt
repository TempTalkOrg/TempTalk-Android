package com.difft.android.call.session

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.CallIntent
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.RoomMetadata
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral coverage for [CallTypeCoordinator] — the single writer that turns a resolved meeting
 * type into observable state.
 *
 * [CallTypeResolver] owns the decision rules and is tested separately; what matters here is the
 * landing: which of `roomCtl.callType`, the shared `CallData` entry and the instant room title get
 * written, in what order, and how the coordinator behaves when the `CallData` entry does not exist
 * yet (the normal situation early in an outbound call).
 *
 * `start()` is deliberately never called. It subscribes via `KProperty0.flow`, which reads the
 * property's delegate through a ThreadLocal that only gets populated by the *real* getter; a mocked
 * [Room] intercepts that getter, so the delegate is never registered and the subscription cannot
 * work against a mock. Every rule the collector applies goes through [CallTypeCoordinator.resolveNow]
 * anyway, which is exercised directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallTypeCoordinatorTest {

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule()

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var room: Room
    private lateinit var roomCtl: CallRoomController
    private lateinit var callIntent: CallIntent
    private lateinit var callDataManager: CallDataManager

    private lateinit var callTypeFlow: MutableStateFlow<String>
    private lateinit var roomMetadataFlow: MutableStateFlow<RoomMetadata>

    /** Value of [CallTypeCoordinator.currentRoomName] at the moment the type was published. */
    private var titleWhenTypePublished: String? = null

    private var roomId: String? = ROOM_ID

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        room = mockk(relaxed = true)
        roomCtl = mockk(relaxed = true)
        callIntent = mockk(relaxed = true)
        // Real instance: it is a dependency-free holder, and using it verifies that the call-list
        // StateFlow actually emits on write-back instead of silently comparing equal.
        callDataManager = CallDataManager()
        callTypeFlow = MutableStateFlow(CallType.ONE_ON_ONE.type)
        titleWhenTypePublished = null
        roomId = ROOM_ID

        every { roomCtl.callType } returns callTypeFlow
        roomMetadataFlow = MutableStateFlow(RoomMetadata())
        every { roomCtl.roomMetadata } returns roomMetadataFlow
        every { roomCtl.updateRoomMetadata(any()) } answers { roomMetadataFlow.value = firstArg() }
        every { callIntent.roomName } returns ROOM_NAME
        every { callIntent.callType } returns CallType.ONE_ON_ONE.type
        stubRemoteParticipants(1)
        stubMetadata(null)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildCoordinator(callRole: CallRole = CallRole.CALLEE): CallTypeCoordinator {
        val coordinator = CallTypeCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            roomProvider = { room },
            roomCtl = roomCtl,
            callDataManager = callDataManager,
            contactorCacheManager = mockk(relaxed = true),
            callIntent = callIntent,
            callRole = callRole,
            mySelfId = SELF_ID,
            json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
            roomIdGetter = { roomId },
        )
        // Records the title as observed by the UI at the instant the type change is published: the
        // UI recomposes off roomCtl.callType, so anything it needs must already be written.
        every { roomCtl.updateCallType(any()) } answers {
            titleWhenTypePublished = coordinator.currentRoomName
            callTypeFlow.value = firstArg()
        }
        return coordinator
    }

    /** Stubs only `size`, the sole property [CallTypeCoordinator] reads off the participant map. */
    private fun stubRemoteParticipants(count: Int) {
        val participants = mockk<Map<Participant.Identity, RemoteParticipant>>(relaxed = true)
        every { participants.size } returns count
        every { room.remoteParticipants } returns participants
    }

    private fun stubMetadata(raw: String?) {
        every { room.metadata } returns raw
    }

    private fun seedCallData(type: String, name: String? = null) {
        callDataManager.updateCallingListData(
            mapOf(
                ROOM_ID to CallData(
                    type = type,
                    version = 1,
                    createdAt = 0L,
                    roomId = ROOM_ID,
                    caller = CallDataCaller(uid = SELF_ID, did = 1),
                    conversation = null,
                    encMeta = null,
                    callName = name,
                ),
            ),
        )
    }

    private fun instantTitleSuffix() =
        ApplicationProvider.getApplicationContext<TestScopeApplication>()
            .getString(com.difft.android.call.R.string.call_instant_call_title)

    // `roomCtl.room` is a fail-loud getter that throws once the call is released, and this class is
    // constructed lazily — the first touch can be `getCallRoomName()` during a recomposition racing
    // teardown, or a `startRoomDependentWiring` that aborted before calling start(). Neither
    // construction nor the title read may resolve the room, so a throwing provider must go unused.
    @Test
    fun `construction and title read never resolve the room`() {
        val coordinator = CallTypeCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            roomProvider = { error("room accessed after release") },
            roomCtl = roomCtl,
            callDataManager = callDataManager,
            contactorCacheManager = mockk(relaxed = true),
            callIntent = callIntent,
            callRole = CallRole.CALLEE,
            mySelfId = SELF_ID,
            json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
            roomIdGetter = { roomId },
        )

        assertEquals(ROOM_NAME, coordinator.currentRoomName)
    }

    // ---------------------------------------------------------------------------------
    // Landing the server's decision.
    // ---------------------------------------------------------------------------------

    // Also the 1v1-invite path: the server flips callType to instant when it accepts the invite,
    // while the invitees have not joined yet and the participant count is still 2. The `instant`
    // branch is unconditional precisely so this upgrade does not need to wait for them to arrive.
    @Test
    fun `server instant metadata is applied to the room controller`() {
        stubMetadata("""{"callType":"instant"}""")

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
    }

    @Test
    fun `server metadata is parsed onto the room controller for media permissions`() {
        stubMetadata("""{"callType":"1on1","canPublishVideo":false}""")

        buildCoordinator().resolveNow()

        verify { roomCtl.updateRoomMetadata(match { !it.canPublishVideo && it.callType == "1on1" }) }
    }

    // Metadata updates are merged, not replaced: `CallMediaController` gates the mic and camera on
    // these flags, so an update that only carries callType must not hand publishing back.
    @Test
    fun `a call type only update keeps an active publish restriction`() {
        stubMetadata("""{"callType":"1on1","canPublishAudio":false,"canPublishVideo":false}""")
        val coordinator = buildCoordinator()
        coordinator.resolveNow()

        stubMetadata("""{"callType":"instant"}""")
        coordinator.resolveNow()

        assertEquals("instant", roomMetadataFlow.value.callType)
        assertFalse(roomMetadataFlow.value.canPublishAudio)
        assertFalse(roomMetadataFlow.value.canPublishVideo)
    }

    @Test
    fun `one-on-one upgrades to instant once a third participant is present`() {
        stubMetadata("""{"callType":"1on1"}""")
        stubRemoteParticipants(2)

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
    }

    // The latch that keeps an upgraded call from visibly reverting when the room empties back out.
    @Test
    fun `instant is not downgraded when the server still reports one-on-one`() {
        callTypeFlow.value = CallType.INSTANT.type
        stubMetadata("""{"callType":"1on1"}""")
        stubRemoteParticipants(1)

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
    }

    // A disconnect nulls Room.metadata, and a manual node switch is a disconnect+connect; the
    // resolved type has to survive that instead of collapsing back to a local guess.
    @Test
    fun `absent metadata leaves an already instant call untouched`() {
        callTypeFlow.value = CallType.INSTANT.type
        stubMetadata(null)

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
        verify(exactly = 0) { roomCtl.updateCallType(any()) }
    }

    // Cross-platform contract: a type newer than this build degrades to multi-party rather than
    // being mistaken for 1v1 (which would open the mic on join).
    @Test
    fun `unknown server call type is treated as multi-party`() {
        stubMetadata("""{"callType":"external"}""")
        stubRemoteParticipants(1)

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
    }

    @Test
    fun `unparseable metadata still applies the local participant rule`() {
        stubMetadata("not json at all")
        stubRemoteParticipants(2)

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
    }

    // ---------------------------------------------------------------------------------
    // Ordering: the title must be in place before the type is published, otherwise the UI
    // draws the instant layout against the pre-instant title.
    // ---------------------------------------------------------------------------------

    @Test
    fun `instant title is written before the type change is published`() {
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator(CallRole.CALLEE)

        coordinator.resolveNow()

        val expected = ROOM_NAME + instantTitleSuffix()
        assertEquals(expected, titleWhenTypePublished)
        assertEquals(expected, coordinator.currentRoomName)
    }

    // The caller's own display name needs a suspending lookup that start() pre-fetches; without it
    // the title falls back to the no-subject default rather than blocking or showing a stale name.
    @Test
    fun `caller falls back to the no-subject title when the display name is not cached`() {
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator(CallRole.CALLER)

        coordinator.resolveNow()

        val fallback = ApplicationProvider.getApplicationContext<TestScopeApplication>()
            .getString(com.difft.android.call.R.string.call_instant_call_title_default)
        assertEquals(fallback, titleWhenTypePublished)
    }

    // RoomUpdate fires on every join and leave, so re-resolving the same value must be a no-op
    // rather than re-publishing the type and rebuilding the UI.
    @Test
    fun `resolving the same instant value twice does not republish the type`() {
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator()

        coordinator.resolveNow()
        coordinator.resolveNow()

        verify(exactly = 1) { roomCtl.updateCallType(CallType.INSTANT.type) }
    }

    // ---------------------------------------------------------------------------------
    // CallData write-back: CallExitHandler reads CallData.type to pick LEAVE vs END.
    // ---------------------------------------------------------------------------------

    @Test
    fun `resolved type is written back onto the call data entry`() {
        seedCallData(CallType.ONE_ON_ONE.type)
        stubMetadata("""{"callType":"instant"}""")

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callDataManager.getCallListData()[ROOM_ID]?.type)
    }

    // The write-back has to be observable. CallData is mutable and getCallListData() hands out the
    // map the StateFlow currently holds, so editing the entry in place would mutate the old value
    // too — the new map would then compare equal to it and never be emitted, leaving the call list
    // stale. Asserting the previous entry is untouched pins the copy-instead-of-mutate contract.
    @Test
    fun `call data write-back replaces the entry instead of mutating it`() {
        seedCallData(CallType.ONE_ON_ONE.type)
        val previousList = callDataManager.getCallListData()
        val previousEntry = previousList[ROOM_ID]
        stubMetadata("""{"callType":"instant"}""")

        buildCoordinator().resolveNow()

        assertEquals(CallType.ONE_ON_ONE.type, previousEntry?.type)
        assertNotSame(previousList, callDataManager.getCallListData())
    }

    @Test
    fun `instant title is propagated to the call data entry`() {
        seedCallData(CallType.ONE_ON_ONE.type, name = ROOM_NAME)
        stubMetadata("""{"callType":"instant"}""")

        buildCoordinator().resolveNow()

        assertEquals(
            ROOM_NAME + instantTitleSuffix(),
            callDataManager.getCallListData()[ROOM_ID]?.callName,
        )
    }

    // An outbound call only gets its CallData entry once ttCallResp is processed, on a different
    // coroutine. A type resolved before then must still reach the room controller — the mic default
    // and the whole UI read it from there — instead of being dropped.
    @Test
    fun `type lands on the room controller even without a call data entry`() {
        roomId = null
        stubMetadata("""{"callType":"instant"}""")

        val coordinator = buildCoordinator()
        coordinator.resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
        assertTrue(coordinator.currentRoomName.endsWith(instantTitleSuffix()))
    }

    // The rename happens on the one resolve where the type changes, and an outbound call can reach
    // that resolve before ttCallResp created the entry — so the title write finds nothing, and no
    // later resolve reports a type change that would retry it. The entry would serve the pre-instant
    // title to the call list and to a rejoin while the in-call UI showed the renamed one.
    @Test
    fun `an instant title missed while the entry was absent is filled in by a later resolve`() {
        roomId = null
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator()
        coordinator.resolveNow()

        // ttCallResp lands: the entry appears with its type already seeded from the resolved value,
        // so this second resolve reports no type change — only the title is still the pre-instant one.
        roomId = ROOM_ID
        seedCallData(CallType.INSTANT.type, name = ROOM_NAME)

        coordinator.resolveNow()

        assertEquals(
            ROOM_NAME + instantTitleSuffix(),
            callDataManager.getCallListData()[ROOM_ID]?.callName,
        )
    }

    // Joining a room that is already instant is not a rename, so the entry keeps the name it was
    // created with. Keeps the re-offered title from becoming "suffix everything on every resolve".
    @Test
    fun `a call already instant on join keeps its entry title`() {
        callTypeFlow.value = CallType.INSTANT.type
        every { callIntent.callType } returns CallType.INSTANT.type
        seedCallData(CallType.INSTANT.type, name = ROOM_NAME)
        stubMetadata("""{"callType":"instant"}""")

        buildCoordinator().resolveNow()

        assertEquals(ROOM_NAME, callDataManager.getCallListData()[ROOM_ID]?.callName)
    }

    // ---------------------------------------------------------------------------------
    // Invite upgrade: applied locally rather than awaited from the server, because until the type
    // flips CallExitHandler routes a hangup down the 1v1 END path and would terminate the meeting
    // for the person just invited instead of leaving it.
    // ---------------------------------------------------------------------------------

    @Test
    fun `an accepted invite lands instant without waiting for the server`() {
        seedCallData(CallType.ONE_ON_ONE.type, name = ROOM_NAME)

        buildCoordinator().applyInviteUpgrade()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
        val entry = callDataManager.getCallListData()[ROOM_ID]
        assertEquals(CallType.INSTANT.type, entry?.type)
        assertEquals(ROOM_NAME + instantTitleSuffix(), entry?.callName)
    }

    // The server flips callType on accepting the invite, so its RoomUpdate follows right behind. It
    // has to confirm what is already in effect, not rebuild the UI a second time.
    @Test
    fun `the server echo following an invite upgrade republishes nothing`() {
        seedCallData(CallType.ONE_ON_ONE.type, name = ROOM_NAME)
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator()

        coordinator.applyInviteUpgrade()
        coordinator.resolveNow()

        verify(exactly = 1) { roomCtl.updateCallType(CallType.INSTANT.type) }
    }

    private companion object {
        const val ROOM_ID = "room-1"
        const val ROOM_NAME = "Room A"
        const val SELF_ID = "self"
    }
}
