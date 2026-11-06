package com.difft.android.chat.recent

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization baseline of the chat-list order with group focus inactive: instant calls, then
 * rooms in a call, then pinned, then drafts, then the rest, each by `sortTime` descending, with the
 * call binding quirks included. The expectations were recorded against the pre-focus
 * implementation; [ChatListAssembler] must keep reproducing them exactly (PRD A17).
 */
class ChatListOrderCharacterizationTest {

    private data class Result(val order: List<String>, val rows: List<RoomViewData>, val instantCache: Map<String, RoomViewData>)

    private fun run(
        rooms: List<RoomViewData>,
        calls: Map<String, CallData>? = null,
        instantCache: Map<String, RoomViewData> = emptyMap(),
    ): Result {
        val out = ChatListAssembler.assemble(
            ChatListInput(
                rooms = rooms,
                callingList = calls,
                instantCallCache = instantCache,
                instantCallDefaultName = DEFAULT_INSTANT,
                myId = ME,
                now = 0L,
            )
        )
        val rows = out.rows.map { (it as ListItem.ChatItem).data }
        return Result(rows.map { it.roomId }, rows, out.instantCallCache)
    }

    private fun room(
        id: String,
        sortTime: Long,
        pinned: Boolean = false,
        draft: String? = null,
        group: Boolean = true,
        callData: CallData? = null,
        lastActiveTime: Long = sortTime,
    ) = RoomViewData(
        roomId = id,
        type = if (group) RoomViewData.Type.Group else RoomViewData.Type.OneOnOne,
        roomName = id,
        lastActiveTime = lastActiveTime,
        pinnedTime = if (pinned) 1L else null,
        draftPreview = draft,
        sortTime = sortTime,
        callData = callData,
    )

    private fun call(roomId: String, type: CallType, conversation: String? = null, caller: String = "+caller", createdAt: Long = 500L, name: String? = null) =
        CallData(
            type = type.type,
            version = 1,
            createdAt = createdAt,
            roomId = roomId,
            caller = CallDataCaller(caller, 1),
            conversation = conversation,
            encMeta = null,
            callName = name,
        )

    @Test
    fun `empty input gives an empty list`() {
        val r = run(emptyList())
        assertEquals(emptyList(), r.order)
        assertEquals(emptyMap(), r.instantCache)
    }

    @Test
    fun `plain rooms sort by sortTime descending`() {
        val r = run(listOf(room("a", 10), room("b", 30), room("c", 20)))
        assertEquals(listOf("b", "c", "a"), r.order)
    }

    @Test
    fun `equal sortTime keeps the input order`() {
        val r = run(listOf(room("a", 10), room("b", 10), room("c", 10)))
        assertEquals(listOf("a", "b", "c"), r.order)
    }

    @Test
    fun `pinned rooms come first and sort among themselves`() {
        val r = run(listOf(room("a", 50), room("p1", 10, pinned = true), room("p2", 20, pinned = true), room("b", 40)))
        assertEquals(listOf("p2", "p1", "a", "b"), r.order)
    }

    @Test
    fun `drafts come after pinned and before the rest, a pinned draft stays pinned`() {
        val r = run(
            listOf(
                room("a", 100),
                room("d1", 5, draft = "x"),
                room("d2", 7, draft = ""),
                room("pd", 1, pinned = true, draft = "y"),
                room("b", 50),
            )
        )
        assertEquals(listOf("pd", "d2", "d1", "a", "b"), r.order)
    }

    @Test
    fun `a group call moves its room above pinned and takes the call time`() {
        val calls = mapOf("call-1" to call("call-1", CallType.GROUP, conversation = "g", createdAt = 7L))
        val r = run(listOf(room("p", 100, pinned = true), room("g", 1, pinned = true), room("a", 50)), calls)
        assertEquals(listOf("g", "p", "a"), r.order)
        val g = r.rows.first()
        assertEquals("call-1", g.callData?.roomId)
        assertEquals(7L, g.lastActiveTime)
        // sortTime is the ViewModel's and is not recomputed here.
        assertEquals(1L, g.sortTime)
    }

    @Test
    fun `meeting rooms sort by sortTime among themselves`() {
        val calls = mapOf(
            "c1" to call("c1", CallType.GROUP, conversation = "g1"),
            "c2" to call("c2", CallType.GROUP, conversation = "g2"),
        )
        val r = run(listOf(room("g1", 1), room("g2", 2), room("a", 99)), calls)
        assertEquals(listOf("g2", "g1", "a"), r.order)
    }

    @Test
    fun `a one-on-one call matches the caller, never myself`() {
        val calls = mapOf(
            "c1" to call("c1", CallType.ONE_ON_ONE, caller = "+bob"),
            "c2" to call("c2", CallType.ONE_ON_ONE, caller = ME),
        )
        val r = run(listOf(room("+bob", 1, group = false), room(ME, 2, group = false), room("a", 99)), calls)
        assertEquals(listOf("+bob", "a", ME), r.order)
        assertNull(r.rows.last().callData)
    }

    @Test
    fun `an instant call with no room gets a synthesized row on top`() {
        val calls = mapOf(
            "i1" to call("i1", CallType.INSTANT, name = "Standup", createdAt = 3L),
            "i2" to call("i2", CallType.INSTANT, name = null, createdAt = 4L),
        )
        val r = run(listOf(room("p", 100, pinned = true), room("a", 50)), calls)
        assertEquals(listOf("i1", "i2", "p", "a"), r.order)
        val i1 = r.rows[0]
        assertTrue(i1.isInstantCall)
        assertEquals("Standup", i1.roomName)
        assertEquals(3L, i1.lastActiveTime)
        assertEquals(DEFAULT_INSTANT, r.rows[1].roomName)
        assertEquals(setOf("i1", "i2"), r.instantCache.keys)
    }

    @Test
    fun `an instant row already in the list is not duplicated`() {
        val instantCall = call("i1", CallType.INSTANT, name = "Standup")
        val cached = RoomViewData(roomId = "i1", type = RoomViewData.Type.Group, roomName = "Standup", isInstantCall = true, callData = instantCall)
        val r = run(listOf(cached, room("a", 50)), mapOf("i1" to instantCall), mapOf("i1" to cached))
        assertEquals(listOf("i1", "a"), r.order)
        assertEquals(setOf("i1"), r.instantCache.keys)
    }

    @Test
    fun `no calls clears call data and the instant cache`() {
        val stale = call("c1", CallType.GROUP, conversation = "g")
        val cached = RoomViewData(roomId = "i1", roomName = "x", isInstantCall = true, callData = call("i1", CallType.INSTANT))
        val r = run(listOf(room("g", 1, callData = stale), room("a", 50)), null, mapOf("i1" to cached))
        assertEquals(listOf("a", "g"), r.order)
        assertNull(r.rows[1].callData)
        assertEquals(emptyMap(), r.instantCache)
    }

    @Test
    fun `a call that ended while others go on clears that room and its cache entry`() {
        val ended = call("c-old", CallType.GROUP, conversation = "g")
        val other = call("c2", CallType.GROUP, conversation = "h")
        val cached = RoomViewData(roomId = "c-old", roomName = "x", isInstantCall = true)
        val r = run(listOf(room("g", 1, callData = ended), room("h", 2), room("a", 50)), mapOf("c2" to other), mapOf("c-old" to cached))
        assertEquals(listOf("h", "a", "g"), r.order)
        assertNull(r.rows[2].callData)
        assertEquals(emptyMap(), r.instantCache)
    }

    @Test
    fun `an ended instant row still in the list falls back to the rest while another call goes on`() {
        val endedInstant = call("i1", CallType.INSTANT)
        val row = RoomViewData(roomId = "i1", type = RoomViewData.Type.Group, roomName = "x", isInstantCall = true, callData = endedInstant, sortTime = 0L)
        val r = run(listOf(row, room("a", 50)), mapOf("c2" to call("c2", CallType.GROUP, conversation = "zz")))
        assertEquals(listOf("a", "i1"), r.order)
        assertNull(r.rows[1].callData)
    }

    @Test
    fun `full order is instant, meeting, pinned, draft, rest`() {
        val calls = mapOf(
            "i1" to call("i1", CallType.INSTANT, name = "I"),
            "c1" to call("c1", CallType.GROUP, conversation = "m"),
        )
        val r = run(
            listOf(
                room("rest", 1000),
                room("draft", 1, draft = "d"),
                room("pin", 2, pinned = true),
                room("m", 3),
                room("rest2", 4),
            ),
            calls,
        )
        assertEquals(listOf("i1", "m", "pin", "draft", "rest", "rest2"), r.order)
    }

    @Test
    fun `input rows are not mutated`() {
        val original = room("g", 1)
        run(listOf(original), mapOf("c" to call("c", CallType.GROUP, conversation = "g", createdAt = 9L)))
        assertNull(original.callData)
        assertEquals(1L, original.lastActiveTime)
    }

    private companion object {
        const val ME = "+me"
        const val DEFAULT_INSTANT = "Instant call"
    }
}
