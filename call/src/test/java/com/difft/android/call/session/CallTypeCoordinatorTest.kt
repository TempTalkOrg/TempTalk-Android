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

    
    private var titleWhenTypePublished: String? = null

    private var roomId: String? = ROOM_ID

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        room = mockk(relaxed = true)
        roomCtl = mockk(relaxed = true)
        callIntent = mockk(relaxed = true)
        
        
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

    private fun buildCoordinator(
        callRole: CallRole = CallRole.CALLEE,
        roomProvider: () -> Room? = { room },
    ): CallTypeCoordinator {
        val coordinator = CallTypeCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            roomProvider = roomProvider,
            roomCtl = roomCtl,
            callDataManager = callDataManager,
            contactorCacheManager = mockk(relaxed = true),
            callIntent = callIntent,
            callRole = callRole,
            mySelfId = SELF_ID,
            json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
            roomIdGetter = { roomId },
        )
        
        
        every { roomCtl.updateCallType(any()) } answers {
            titleWhenTypePublished = coordinator.currentRoomName
            callTypeFlow.value = firstArg()
        }
        return coordinator
    }

    
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

    
    
    
    @Test
    fun `T2-17 a metadata emission after release is dropped without throwing`() {
        seedCallData(CallType.ONE_ON_ONE.type)
        stubMetadata("""{"callType":"instant"}""")

        buildCoordinator(roomProvider = { null }).resolveNow()

        var reads = 0
        buildCoordinator(roomProvider = { room.takeIf { reads++ == 0 } }).resolveNow()

        assertEquals(CallType.ONE_ON_ONE.type, callTypeFlow.value)
        assertEquals(CallType.ONE_ON_ONE.type, callDataManager.getCallListData()[ROOM_ID]?.type)
        verify(exactly = 0) { roomCtl.updateCallType(any()) }
    }

    
    
    

    
    
    
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

    
    @Test
    fun `instant is not downgraded when the server still reports one-on-one`() {
        callTypeFlow.value = CallType.INSTANT.type
        stubMetadata("""{"callType":"1on1"}""")
        stubRemoteParticipants(1)

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
    }

    
    
    @Test
    fun `absent metadata leaves an already instant call untouched`() {
        callTypeFlow.value = CallType.INSTANT.type
        stubMetadata(null)

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
        verify(exactly = 0) { roomCtl.updateCallType(any()) }
    }

    
    
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

    
    
    
    

    @Test
    fun `instant title is written before the type change is published`() {
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator(CallRole.CALLEE)

        coordinator.resolveNow()

        val expected = ROOM_NAME + instantTitleSuffix()
        assertEquals(expected, titleWhenTypePublished)
        assertEquals(expected, coordinator.currentRoomName)
    }

    
    
    @Test
    fun `caller falls back to the no-subject title when the display name is not cached`() {
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator(CallRole.CALLER)

        coordinator.resolveNow()

        val fallback = ApplicationProvider.getApplicationContext<TestScopeApplication>()
            .getString(com.difft.android.call.R.string.call_instant_call_title_default)
        assertEquals(fallback, titleWhenTypePublished)
    }

    
    
    @Test
    fun `resolving the same instant value twice does not republish the type`() {
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator()

        coordinator.resolveNow()
        coordinator.resolveNow()

        verify(exactly = 1) { roomCtl.updateCallType(CallType.INSTANT.type) }
    }

    
    
    

    @Test
    fun `resolved type is written back onto the call data entry`() {
        seedCallData(CallType.ONE_ON_ONE.type)
        stubMetadata("""{"callType":"instant"}""")

        buildCoordinator().resolveNow()

        assertEquals(CallType.INSTANT.type, callDataManager.getCallListData()[ROOM_ID]?.type)
    }

    
    
    
    
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

    
    
    
    @Test
    fun `type lands on the room controller even without a call data entry`() {
        roomId = null
        stubMetadata("""{"callType":"instant"}""")

        val coordinator = buildCoordinator()
        coordinator.resolveNow()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
        assertTrue(coordinator.currentRoomName.endsWith(instantTitleSuffix()))
    }

    
    
    
    
    @Test
    fun `an instant title missed while the entry was absent is filled in by a later resolve`() {
        roomId = null
        stubMetadata("""{"callType":"instant"}""")
        val coordinator = buildCoordinator()
        coordinator.resolveNow()

        
        
        roomId = ROOM_ID
        seedCallData(CallType.INSTANT.type, name = ROOM_NAME)

        coordinator.resolveNow()

        assertEquals(
            ROOM_NAME + instantTitleSuffix(),
            callDataManager.getCallListData()[ROOM_ID]?.callName,
        )
    }

    
    
    @Test
    fun `a call already instant on join keeps its entry title`() {
        callTypeFlow.value = CallType.INSTANT.type
        every { callIntent.callType } returns CallType.INSTANT.type
        seedCallData(CallType.INSTANT.type, name = ROOM_NAME)
        stubMetadata("""{"callType":"instant"}""")

        buildCoordinator().resolveNow()

        assertEquals(ROOM_NAME, callDataManager.getCallListData()[ROOM_ID]?.callName)
    }

    
    
    
    
    

    @Test
    fun `an accepted invite lands instant without waiting for the server`() {
        seedCallData(CallType.ONE_ON_ONE.type, name = ROOM_NAME)

        buildCoordinator().applyInviteUpgrade()

        assertEquals(CallType.INSTANT.type, callTypeFlow.value)
        val entry = callDataManager.getCallListData()[ROOM_ID]
        assertEquals(CallType.INSTANT.type, entry?.type)
        assertEquals(ROOM_NAME + instantTitleSuffix(), entry?.callName)
    }

    
    
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
