package com.difft.android.chat.search

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.base.utils.ResUtils
import org.difft.app.database.RoomSearchResult
import org.difft.app.database.getContactorsFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.MessageModel

object SearchUtils {
    fun createSearchChatHistoryViewDataList(context: Context, results: List<RoomSearchResult>): List<SearchChatHistoryViewData> {
        val rooms = results.map { it.room }
        val contactorIds = rooms.filter { it.roomType == 0 }.map { it.roomId }
        val groupIds = rooms.filter { it.roomType == 1 }.map { it.roomId }

        val contacts = wcdb.getContactorsFromAllTable(contactorIds)
        val groups = if (groupIds.isNotEmpty()) {
            wcdb.group.getAllObjects(DBGroupModel.gid.`in`(*groupIds.toTypedArray()))
        } else emptyList()

        return results.mapNotNull { result ->
            val room = result.room
            val conversationId = room.roomId
            val viewData = SearchChatHistoryViewData(conversationId)

            when (room.roomType) {
                0 -> {
                    val contact = contacts.find { it.id == conversationId }
                        ?: ContactorModel().also { it.id = conversationId }
                    viewData.type = SearchChatHistoryViewData.Type.OneOnOne
                    viewData.label = contact.getDisplayNameForUI()
                    viewData.contactor = contact
                }
                1 -> {
                    val group = groups.find { it.gid == conversationId }
                        ?: GroupModel().apply { gid = conversationId; name = ResUtils.getString(R.string.group_unknown_group) }
                    viewData.type = SearchChatHistoryViewData.Type.Group
                    viewData.label = if (TextUtils.isEmpty(group.name)) ResUtils.getString(R.string.group_unknown_group) else group.name
                    viewData.group = group
                }
                else -> return@mapNotNull null
            }

            val singleMessage = result.singleMessage
            if (result.messageCount == 1 && singleMessage != null) {
                viewData.detail = singleMessage.messageText
                viewData.onlyOneResult = true
                viewData.messageTimeStamp = singleMessage.timeStamp
            } else {
                viewData.detail = context.getString(R.string.search_related_messages, result.messageCount)
            }

            viewData
        }
    }

    fun createSearchChatHistoryViewDataList(messageModels: List<MessageModel>): List<SearchChatHistoryViewData> {
        val contactorIds = messageModels.mapNotNull { it.fromWho }
        val contacts = wcdb.getContactorsFromAllTable(contactorIds)

        val result = messageModels.map { messageModel ->
            val conversationId = messageModel.roomId
            val senderId = messageModel.fromWho

            val newChatViewData = SearchChatHistoryViewData(conversationId)
            val contact: ContactorModel = contacts.find { contact -> contact.id == messageModel.fromWho } ?: ContactorModel().also { it.id = senderId }

            newChatViewData.label = contact.getDisplayNameForUI()
            newChatViewData.detail = messageModel.messageText
            newChatViewData.contactor = contact
            newChatViewData.onlyOneResult = true
            newChatViewData.messageTimeStamp = messageModel.timeStamp

            newChatViewData
        }

        return result
    }
}

fun TextView.setHighLightText(text: String?, key: String) {
    if (text.isNullOrEmpty()) return
    if (key.isNotEmpty()) {
        val spannableBuilder = SpannableStringBuilder(text)
        val startPos = text.indexOf(key, 0, true)
        if (startPos != -1) {
            val endPos = startPos + key.length
            spannableBuilder.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, com.difft.android.base.R.color.t_info)),
                startPos,
                endPos,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        this.text = spannableBuilder
    } else {
        this.text = text
    }
}

fun TextView.setHighLightText(text: String?, keys: List<String>) {
    if (text.isNullOrEmpty() || keys.all { it.isEmpty() }) {
        this.text = text
        return
    }
    val positions = mutableListOf<Pair<Int, Int>>()
    for (key in keys) {
        if (key.isEmpty()) continue
        var startIndex = 0
        while (startIndex < text.length) {
            val pos = text.indexOf(key, startIndex, ignoreCase = true)
            if (pos == -1) break
            positions.add(pos to pos + key.length)
            startIndex = pos + key.length
        }
    }
    if (positions.isEmpty()) {
        this.text = text
        return
    }
    positions.sortBy { it.first }
    val merged = mutableListOf<Pair<Int, Int>>()
    for ((start, end) in positions) {
        if (merged.isEmpty() || start > merged.last().second) {
            merged.add(start to end)
        } else if (end > merged.last().second) {
            merged[merged.lastIndex] = merged.last().first to end
        }
    }
    val spannable = SpannableStringBuilder(text)
    val color = ContextCompat.getColor(context, com.difft.android.base.R.color.t_info)
    for ((start, end) in merged) {
        spannable.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    this.text = spannable
}
