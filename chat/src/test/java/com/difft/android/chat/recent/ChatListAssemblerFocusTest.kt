package com.difft.android.chat.recent

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallType
import com.difft.android.chat.focus.EffectiveFocus
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Group focus rules of [ChatListAssembler] (design §3/§4, PRD 7.4, A1/A8/A9/A9b–d/A10c/A16/A17). */
class ChatListAssemblerFocusTest {

    private fun focus(
        gid: String? = "F",
        active: Boolean = true,
        invalid: Boolean = false,
        fold: Boolean = true,
        until: Long? = null,
        feature: Boolean = true,
    ) = EffectiveFocus(
        featureEnabled = feature,
        active = active,
        invalid = invalid,
        groupIds = if (active && !invalid && gid != null) setOf(gid) else emptySet(),
        until = until,
        foldOthers = fold,
    )

    private fun room(
        id: String,
        sortTime: Long,
        group: Boolean = true,
        pinned: Boolean = false,
        unread: Int = 0,
        mention: Int = MENTIONS_TYPE_NONE,
        manualUnread: Boolean = false,
        draft: String? = null,
    ) = RoomViewData(
        roomId = id,
        type = if (group) RoomViewData.Type.Group else RoomViewData.Type.OneOnOne,
        roomName = "name-$id",
        lastDisplayContent = "msg-$id",
        lastActiveTime = sortTime,
        lastActiveTimeText = "t$sortTime",
        unreadMessageNum = unread,
        isManualUnread = manualUnread,
        pinnedTime = if (pinned) 1L else null,
        mentionType = mention,
        draftPreview = draft,
        sortTime = sortTime,
    )

    private fun groupCall(roomId: String, gid: String) = CallData(
        type = CallType.GROUP.type, version = 1, createdAt = 1L, roomId = roomId,
        caller = CallDataCaller("+x", 1), conversation = gid, encMeta = null,
    )

    private fun assemble(
        rooms: List<RoomViewData>,
        focus: EffectiveFocus,
        synthesized: RoomViewData? = null,
        calls: Map<String, CallData>? = null,
        deleted: Set<String> = emptySet(),
    ) = ChatListAssembler.assemble(
        ChatListInput(
            rooms = rooms,
            callingList = calls,
            instantCallCache = emptyMap(),
            instantCallDefaultName = "Instant",
            myId = ME,
            now = 0L,
            focus = focus,
            synthesizedFocusRoom = synthesized,
            deletedFocusGids = deleted,
        )
    )

    private fun ListItem.key(): String = when (this) {
        is ListItem.ChatItem -> data.roomId
        is ListItem.FocusChat -> "*${data.roomId}"
        is ListItem.FoldedGroups -> "folded(${summary.count})"
        else -> toString()
    }

    private fun ChatListAssembly.keys() = rows.map { it.key() }

    // F is the focus group; its pin is the one FocusPinCoordinator writes when focus starts.
    private val baseRooms = listOf(
        room("A", 90),
        room("F", 10, pinned = true),
        room("P", 5, pinned = true),
        room("+bob", 80, group = false),
        room(ME, 1, group = false, pinned = true),
        room("B", 70),
    )

    @Test
    fun `feature on but not focusing keeps the pre-focus order and offers focus on groups only`() {
        // F keeps no pin here: focus never started, so the coordinator wrote nothing.
        val out = assemble(baseRooms.map { if (it.roomId == "F") it.copy(pinnedTime = null) else it }, focus(active = false))
        assertEquals(listOf("P", ME, "A", "+bob", "B", "F"), out.keys())
        val byId = out.rows.associate { it.chatItemData()!!.roomId to it.chatItemData()!! }
        assertEquals(FocusRowAction.START, byId.getValue("A").focusAction)
        assertEquals(FocusRowAction.NONE, byId.getValue("+bob").focusAction)
        assertEquals(FocusRowAction.NONE, byId.getValue(ME).focusAction)
        assertTrue(out.folded.isEmpty())
    }

    @Test
    fun `feature off shows no focus entry at all`() {
        val out = assemble(baseRooms, focus(feature = false, active = false))
        assertTrue(out.rows.all { it.chatItemData()!!.focusAction == FocusRowAction.NONE })
    }

    @Test
    fun `the focus group renders as the focus card and sits where its stored pin puts it`() {
        // Not pinned in the DB (the coordinator has not written yet, or the user unpinned it): it keeps its activity position.
        val rooms = baseRooms.map { if (it.roomId == "F") it.copy(pinnedTime = null) else it }
        val unpinned = assemble(rooms, focus(fold = false))
        assertEquals(listOf("P", ME, "A", "+bob", "B", "*F"), unpinned.keys())
        val card = unpinned.rows.last() as ListItem.FocusChat
        assertFalse(card.data.isPinned)
        assertEquals(FocusRowAction.END, card.data.focusAction)

        // Pinned in the DB: the pinned section, ordered by activity like every pinned row.
        val pinned = assemble(baseRooms, focus(fold = false))
        assertEquals(listOf("*F", "P", ME, "A", "+bob", "B"), pinned.keys())
        assertTrue((pinned.rows.first() as ListItem.FocusChat).data.isPinned)
    }

    @Test
    fun `the folded row carries the focus end time, and none while focus runs until ended by hand`() {
        val timed = assemble(baseRooms, focus(until = 3_600_000L))
        assertNotNull((timed.rows.single { it is ListItem.FoldedGroups } as ListItem.FoldedGroups).summary.resumeLabel)
        val manual = assemble(baseRooms, focus())
        assertNull((manual.rows.single { it is ListItem.FoldedGroups } as ListItem.FoldedGroups).summary.resumeLabel)
    }

    @Test
    fun `a synthesized focus row is used only when the focus group has no room row`() {
        val stand = RoomViewData(roomId = "G", type = RoomViewData.Type.Group, roomName = "G", isSynthesized = true)
        val withStand = assemble(baseRooms, focus(gid = "G", fold = false), synthesized = stand)
        // The stand-in counts as pinned with no activity yet (sortTime 0): last of the pinned section.
        assertEquals(listOf("F", "P", ME, "*G", "A", "+bob", "B"), withStand.keys())
        assertTrue(withStand.rows[3].chatItemData()!!.isSynthesized)

        val existing = assemble(baseRooms, focus(gid = "F", fold = false), synthesized = stand.copy(roomId = "F"))
        assertEquals(1, existing.keys().count { it == "*F" })
        assertFalse(existing.rows.first().chatItemData()!!.isSynthesized)

        val inactive = assemble(baseRooms, focus(gid = "G", active = false), synthesized = stand)
        assertFalse(inactive.keys().any { it.contains("G") })
    }

    @Test
    fun `no stand-in row for a focus group the user deleted, but a real room row of it still shows`() {
        val stand = RoomViewData(roomId = "G", type = RoomViewData.Type.Group, roomName = "G", isSynthesized = true)
        val deleted = assemble(baseRooms, focus(gid = "G", fold = false), synthesized = stand, deleted = setOf("G"))
        assertFalse(deleted.keys().any { it.contains("G") })

        // Another group's record does not hide this one's stand-in.
        val other = assemble(baseRooms, focus(gid = "G", fold = false), synthesized = stand, deleted = setOf("Z"))
        assertTrue(other.keys().contains("*G"))

        // The room came back on its own (a new message recreated it): it shows normally despite the record.
        val back = assemble(baseRooms, focus(gid = "F", fold = false), synthesized = stand.copy(roomId = "F"), deleted = setOf("F"))
        assertEquals(1, back.keys().count { it == "*F" })
        assertFalse(back.rows.first().chatItemData()!!.isSynthesized)
    }

    @Test
    fun `folding moves every other group, pinned ones included, into one row sorted by its newest member`() {
        val out = assemble(baseRooms, focus())
        // Private chats, Note to Self and the focus group stay; A (90), P (pinned) and B fold.
        assertEquals(listOf("*F", ME, "folded(3)", "+bob"), out.keys())
        val summary = (out.rows[2] as ListItem.FoldedGroups).summary
        assertEquals("name-A", summary.latestGroupName)
        assertEquals("msg-A", summary.latestContent)
        assertEquals("t90", summary.lastActiveTimeText)
        assertEquals(90L, summary.sortTime)
    }

    @Test
    fun `the folded row sums unread and prefers an at-me over an at-all`() {
        val rooms = listOf(
            room("F", 10),
            room("A", 50, unread = 3, mention = MENTIONS_TYPE_ALL),
            room("B", 40, unread = 4, mention = MENTIONS_TYPE_ME),
            room("C", 30, manualUnread = true),
        )
        val summary = (assemble(rooms, focus()).rows.single { it is ListItem.FoldedGroups } as ListItem.FoldedGroups).summary
        assertEquals(3, summary.count)
        assertEquals(7, summary.unreadCount)
        assertTrue(summary.hasManualUnread)
        assertEquals(MENTIONS_TYPE_ME, summary.mentionType)

        val allOnly = listOf(room("F", 10), room("A", 50, mention = MENTIONS_TYPE_ALL), room("B", 40))
        val allSummary = (assemble(allOnly, focus()).rows.single { it is ListItem.FoldedGroups } as ListItem.FoldedGroups).summary
        assertEquals(MENTIONS_TYPE_ALL, allSummary.mentionType)
    }

    @Test
    fun `a group in a call leaves the fold for the call section and returns after it`() {
        val calls = mapOf("c1" to groupCall("c1", "B"))
        val during = assemble(baseRooms, focus(), calls = calls)
        assertEquals(listOf("B", "*F", ME, "folded(2)", "+bob"), during.keys())
        assertFalse(during.folded.any { it.roomId == "B" })

        val after = assemble(baseRooms, focus(), calls = null)
        assertEquals(listOf("*F", ME, "folded(3)", "+bob"), after.keys())
    }

    @Test
    fun `no folded row when every other group is in a call`() {
        val rooms = listOf(room("F", 10, pinned = true), room("A", 50), room("+bob", 20, group = false))
        val out = assemble(rooms, focus(), calls = mapOf("c" to groupCall("c", "A")))
        assertEquals(listOf("A", "*F", "+bob"), out.keys())
    }

    @Test
    fun `the focus group in a call stays the focus card in the call section`() {
        val out = assemble(baseRooms, focus(), calls = mapOf("c" to groupCall("c", "F")))
        assertEquals("*F", out.keys().first())
        assertNotNull(out.rows.first().chatItemData()!!.callData)
    }

    @Test
    fun `the folded list shows pinned groups first, then by time`() {
        val rooms = listOf(room("F", 10), room("A", 90), room("P1", 5, pinned = true), room("B", 70), room("P2", 6, pinned = true))
        val out = assemble(rooms, focus())
        assertEquals(listOf("P2", "P1", "A", "B"), out.folded.map { it.roomId })
        assertTrue(out.folded.take(2).all { it.isPinned })
    }

    @Test
    fun `without folding the other groups stay in place`() {
        val out = assemble(baseRooms, focus(fold = false))
        assertTrue(out.rows.none { it is ListItem.FoldedGroups })
        assertTrue(out.folded.isEmpty())
    }

    @Test
    fun `in the invalid state every group is other and there is no focus card`() {
        val out = assemble(baseRooms, focus(invalid = true))
        assertTrue(out.rows.none { it is ListItem.FocusChat })
        assertEquals(listOf(ME, "folded(4)", "+bob"), out.keys())
        assertTrue(out.folded.any { it.roomId == "F" })
    }

    @Test
    fun `a draft group folds too`() {
        val rooms = listOf(room("F", 10, pinned = true), room("D", 5, draft = "x"), room("+bob", 20, group = false))
        assertEquals(listOf("*F", "+bob", "folded(1)"), assemble(rooms, focus()).keys())
    }

    @Test
    fun `effective mute follows focus for groups only`() {
        val on = focus()
        assertNull(ChatListAssembler.effectiveMuted(isGroup = false, roomId = "+bob", focus = on))
        assertEquals(false, ChatListAssembler.effectiveMuted(isGroup = true, roomId = "F", focus = on))
        assertEquals(true, ChatListAssembler.effectiveMuted(isGroup = true, roomId = "A", focus = on))
        assertNull(ChatListAssembler.effectiveMuted(isGroup = true, roomId = "A", focus = focus(active = false)))
        assertEquals(true, ChatListAssembler.effectiveMuted(isGroup = true, roomId = "F", focus = focus(invalid = true)))
    }

    @Test
    fun `effective mute drives isMuted, otherwise muteStatus does`() {
        val muted = RoomViewData(roomId = "A", muteStatus = MuteStatus.MUTED.value)
        assertTrue(muted.isMuted)
        assertFalse(muted.copy(effectiveMuted = false).isMuted)
        assertTrue(RoomViewData(roomId = "B").copy(effectiveMuted = true).isMuted)
    }

    private companion object {
        const val ME = "+me"
    }
}
