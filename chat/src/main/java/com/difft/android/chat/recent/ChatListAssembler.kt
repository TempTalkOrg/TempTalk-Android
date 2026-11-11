package com.difft.android.chat.recent

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallType
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusTimeFormat
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE


internal data class ChatListInput(
    
    val rooms: List<RoomViewData>,
    val callingList: Map<String, CallData>?,
    
    val instantCallCache: Map<String, RoomViewData>,
    val instantCallDefaultName: String,
    val myId: String,
    val now: Long,
    val focus: EffectiveFocus = EffectiveFocus.INACTIVE,
    
    val synthesizedFocusRoom: RoomViewData? = null,
    
    val deletedFocusGids: Set<String> = emptySet(),
)

internal data class ChatListAssembly(
    
    val rows: List<ListItem>,
    
    val folded: List<RoomViewData>,
    val instantCallCache: Map<String, RoomViewData>,
)
















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
            
            val summary = summarize(folded, focus, input.now)
            val index = sections.rest.indexOfFirst { it.sortTime < summary.sortTime }.takeIf { it >= 0 } ?: rest.size
            rest.add(index, ListItem.FoldedGroups(summary))
        }
        rows.addAll(rest)

        val foldedList = folded.filter { it.isPinned }.sortedByDescending { it.sortTime } +
            folded.filterNot { it.isPinned }.sortedByDescending { it.sortTime }
        return ChatListAssembly(rows, foldedList, instantCache)
    }

    
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
