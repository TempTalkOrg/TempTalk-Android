package com.difft.android.chat.recent

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallType
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusTimeFormat
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE

/** Everything one chat-list assembly reads; all of it comes from flows the list already observes. */
internal data class ChatListInput(
    /** The ViewModel's rooms (instant-call cache rows included), newest first. */
    val rooms: List<RoomViewData>,
    val callingList: Map<String, CallData>?,
    /** Instant-call rows synthesized by an earlier assembly; the result carries the updated cache. */
    val instantCallCache: Map<String, RoomViewData>,
    val instantCallDefaultName: String,
    val myId: String,
    val now: Long,
    val focus: EffectiveFocus = EffectiveFocus.INACTIVE,
    /** Stand-in for the focus group when it has no visible room row; ignored when one exists. */
    val synthesizedFocusRoom: RoomViewData? = null,
    /** Focus groups whose conversation the user deleted here: no stand-in row is added for them. */
    val deletedFocusGids: Set<String> = emptySet(),
)

internal data class ChatListAssembly(
    /** Main-list rows, without the search box and footer. */
    val rows: List<ListItem>,
    /** The folded groups as the folded list shows them: pinned first, then by time. Empty when not folding. */
    val folded: List<RoomViewData>,
    val instantCallCache: Map<String, RoomViewData>,
)

/**
 * Builds the chat list (design §3/§4). Order: instant calls → rooms in a call → pinned → drafts →
 * the rest, each by `sortTime` descending. With focus inactive the output is exactly the order the
 * list had before group focus (pinned by `ChatListOrderCharacterizationTest`, PRD A17). With focus:
 *
 * - the focus group renders as [ListItem.FocusChat]; its pin is the stored one (written by
 *   `FocusPinCoordinator`), and a stand-in row without a room row sits in the pinned area;
 * - folding (`foldOthers`) moves every other group, pinned ones included, into one
 *   [ListItem.FoldedGroups] row sorted with the rest by its newest member; private chats,
 *   Note to Self, bots, instant calls and groups with an ongoing call stay in the main list;
 * - in the invalid state every group is "other" and there is no focus row;
 * - a stand-in row is not added for a focus group the user deleted ([ChatListInput.deletedFocusGids]); a
 *   real room row of that group still shows.
 *
 * Pure: no Android, DB or clock access, so the main list and the folded list share it.
 */
internal object ChatListAssembler {

    fun assemble(input: ChatListInput): ChatListAssembly {
        val focus = input.focus
        val working = input.rooms.map { it.copy() }.toMutableList()
        val synthesized = input.synthesizedFocusRoom
        if (synthesized != null &&
            focus.isFocusedGroup(synthesized.roomId) &&
            synthesized.roomId !in input.deletedFocusGids &&
            working.none { it.roomId == synthesized.roomId }
        ) {
            working.add(synthesized.copy())
        }
        val instantCache = applyCalls(working, input)
        val decorated = if (focus.featureEnabled) working.map { decorate(it, focus) } else working

        val folding = focus.active && focus.foldOthers
        val (folded, mainRooms) = if (folding) decorated.partition { it.isFoldable(focus) } else emptyList<RoomViewData>() to decorated
        val sections = sections(mainRooms, instantCache)

        val rows = ArrayList<ListItem>(mainRooms.size + instantCache.size + 1)
        (sections.instant + sections.meeting + sections.pinned + sections.draft).mapTo(rows) { it.toListItem(focus) }
        val rest = sections.rest.mapTo(ArrayList()) { it.toListItem(focus) }
        if (folded.isNotEmpty()) {
            // The folded row sorts with the rest by its newest member; on a tie it goes after the room.
            val summary = summarize(folded, focus, input.now)
            val index = sections.rest.indexOfFirst { it.sortTime < summary.sortTime }.takeIf { it >= 0 } ?: rest.size
            rest.add(index, ListItem.FoldedGroups(summary))
        }
        rows.addAll(rest)

        val foldedList = folded.filter { it.isPinned }.sortedByDescending { it.sortTime } +
            folded.filterNot { it.isPinned }.sortedByDescending { it.sortTime }
        return ChatListAssembly(rows, foldedList, instantCache)
    }

    /** Effective mute for a ViewModel row: null (= [RoomViewData.muteStatus] decides) unless focus is on and the room is a group. */
    fun effectiveMuted(isGroup: Boolean, roomId: String, focus: EffectiveFocus): Boolean? =
        if (isGroup && focus.active) !focus.isFocusedGroup(roomId) else null

    private fun RoomViewData.isFoldable(focus: EffectiveFocus): Boolean =
        type == RoomViewData.Type.Group && !isInstantCall && callData == null && !focus.isFocusedGroup(roomId)

    private fun RoomViewData.toListItem(focus: EffectiveFocus): ListItem =
        if (focus.isFocusedGroup(roomId)) ListItem.FocusChat(this) else ListItem.ChatItem(this)

    private fun decorate(room: RoomViewData, focus: EffectiveFocus): RoomViewData {
        val isGroupRow = room.type == RoomViewData.Type.Group && !room.isInstantCall
        val focused = focus.isFocusedGroup(room.roomId)
        val action = when {
            !isGroupRow -> FocusRowAction.NONE
            focused -> FocusRowAction.END
            else -> FocusRowAction.START
        }
        return room.copy(focusAction = action)
    }

    private fun summarize(folded: List<RoomViewData>, focus: EffectiveFocus, now: Long): FoldedGroupsSummary {
        val latest = folded.maxBy { it.sortTime }
        val mention = when {
            folded.any { it.mentionType == MENTIONS_TYPE_ME } -> MENTIONS_TYPE_ME
            folded.any { it.mentionType == MENTIONS_TYPE_ALL } -> MENTIONS_TYPE_ALL
            else -> MENTIONS_TYPE_NONE
        }
        return FoldedGroupsSummary(
            count = folded.size,
            unreadCount = folded.sumOf { it.unreadMessageNum },
            hasManualUnread = folded.any { it.isManualUnread },
            mentionType = mention,
            latestGroupName = latest.roomName,
            latestContent = latest.lastDisplayContent,
            lastActiveTimeText = latest.lastActiveTimeText,
            sortTime = latest.sortTime,
            resumeLabel = focus.until?.let { FocusTimeFormat.endLabel(it, now) },
        )
    }

    private class Sections(
        val instant: List<RoomViewData>,
        val meeting: List<RoomViewData>,
        val pinned: List<RoomViewData>,
        val draft: List<RoomViewData>,
        val rest: List<RoomViewData>,
    )

    /** Splits [working] into the list sections; the filters are the pre-focus ones, unchanged. */
    private fun sections(working: List<RoomViewData>, instantCache: Map<String, RoomViewData>): Sections {
        val pinnedRooms = working.filter { item -> item.isPinned && item.callData == null }
        val meetingRooms = working.filter { item -> item.callData != null && item.callData?.type != CallType.INSTANT.type }
        val draftRooms = working.filter { item -> item.draftPreview != null && !item.isPinned && item.callData == null }
        val otherRooms = working.filter { item ->
            item.callData == null && item.draftPreview == null && meetingRooms.contains(item).not() && pinnedRooms.contains(item).not()
        }
        val currentInstantCallRooms = working.filter { item -> item.callData != null && item.callData?.type == CallType.INSTANT.type }
        val existingRoomIds = currentInstantCallRooms.map { it.roomId }.toSet()
        val instantRooms = currentInstantCallRooms + instantCache.values.filterNot { it.roomId in existingRoomIds }
        return Sections(
            instant = instantRooms.sortedByDescending { it.sortTime },
            meeting = meetingRooms.sortedByDescending { it.sortTime },
            pinned = pinnedRooms.sortedByDescending { it.sortTime },
            draft = draftRooms.sortedByDescending { it.sortTime },
            rest = otherRooms.sortedByDescending { it.sortTime },
        )
    }

    /**
     * Binds the calling list to the rows (mutating the working copies) and returns the updated
     * instant-call cache. Same rules as before group focus: a group call matches its
     * conversation, a one-on-one call its caller (never myself), an instant call without a room
     * gets a synthesized row, and call data whose call is gone is dropped.
     */
    private fun applyCalls(working: MutableList<RoomViewData>, input: ChatListInput): Map<String, RoomViewData> {
        val callingList = input.callingList
        val cache = input.instantCallCache.toMutableMap()
        if (callingList.isNullOrEmpty()) {
            working.forEach { it.callData = null }
            cache.clear()
        }
        val currentRoomIds = working.map { it.roomId }
        callingList?.values?.forEach { callData ->
            val roomId = callData.roomId
            if (callData.type == CallType.INSTANT.type && !currentRoomIds.contains(roomId) && !cache.containsKey(roomId)) {
                cache[roomId] = RoomViewData(
                    roomId = roomId,
                    type = RoomViewData.Type.Group,
                    roomName = callData.callName ?: input.instantCallDefaultName,
                    lastActiveTime = callData.createdAt,
                    unreadMessageNum = 0,
                    pinnedTime = null,
                    muteStatus = 0,
                    mentionType = MENTIONS_TYPE_NONE,
                    isInstantCall = true,
                    callData = callData,
                )
            }
        }
        working.forEach { item ->
            val callData = item.callData
            if (callData != null) {
                val callRoomId = callData.roomId
                if (callRoomId.isNotEmpty()) {
                    if (callingList?.containsKey(callRoomId) != true) {
                        item.callData = null
                        cache.remove(callRoomId)
                    } else if (callData.type == CallType.INSTANT.type && !callingList.containsKey(item.roomId)) {
                        item.callData = null
                    }
                }
            } else {
                callingList?.values?.forEach { call -> bindCall(item, call, input.myId) }
            }
        }
        return cache
    }

    private fun bindCall(item: RoomViewData, call: CallData, myId: String) {
        val conversationId = item.roomId
        if (conversationId.isEmpty()) return
        val matches = when (call.type) {
            CallType.GROUP.type -> !call.conversation.isNullOrEmpty() && conversationId == call.conversation
            CallType.ONE_ON_ONE.type -> {
                val id = call.caller.uid
                !id.isNullOrEmpty() && id != myId && id == conversationId
            }
            else -> false
        }
        if (matches) {
            item.callData = call
            item.lastActiveTime = call.createdAt
        }
    }
}
