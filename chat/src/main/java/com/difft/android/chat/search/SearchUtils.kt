package com.difft.android.chat.search

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.base.utils.ResUtils
import org.difft.app.database.getContactorsFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import io.reactivex.rxjava3.core.Single
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.RoomModel

object SearchUtils {
    fun createSearchChatHistoryViewDataList(context: Context, roomMessageMap: Map<RoomModel, List<MessageModel>>): Single<List<SearchChatHistoryViewData>> = Single.fromCallable {
        val rooms = roomMessageMap.keys
        val contactorIds = rooms.filter { it.roomType == 0 }.map { it.roomId }
        val groupIds = rooms.filter { it.roomType == 1 }.map { it.roomId }

        val contacts = wcdb.getContactorsFromAllTable(contactorIds)
        val groups = wcdb.group.getAllObjects(DBGroupModel.gid.`in`(*groupIds.toTypedArray()))

        val list = roomMessageMap.mapNotNull { (room, messages) ->

            val conversationId = room.roomId

            val newChatViewData = SearchChatHistoryViewData(conversationId)

            when (room.roomType) {
                0 -> {
                    val contact: ContactorModel = contacts.find { contact -> contact.id == conversationId } ?: ContactorModel().also { it.id = conversationId }

                    newChatViewData.type = SearchChatHistoryViewData.Type.OneOnOne
                    newChatViewData.label = contact.getDisplayNameForUI()
                    newChatViewData.contactor = contact
                }

                1 -> {
                    val group: GroupModel = groups.find { group -> group.gid == conversationId } ?: GroupModel().apply { gid = conversationId; name = ResUtils.getString(com.difft.android.chat.R.string.group_unknown_group) }

                    newChatViewData.type = SearchChatHistoryViewData.Type.Group
                    newChatViewData.label = if (TextUtils.isEmpty(group.name)) ResUtils.getString(com.difft.android.chat.R.string.group_unknown_group) else group.name
                    newChatViewData.group = group
                }

                else -> {}
            }
            if (messages.size == 1) {
                newChatViewData.detail = messages.first().messageText
                newChatViewData.onlyOneResult = true
                newChatViewData.messageTimeStamp = messages.first().timeStamp
            } else {
                newChatViewData.detail = context.getString(R.string.search_related_messages, messages.size)
            }

            newChatViewData
        }
        list
    }

    fun createSearchChatHistoryViewDataList(messageModels: List<MessageModel>): Single<List<SearchChatHistoryViewData>> = Single.fromCallable {
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

        result
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
