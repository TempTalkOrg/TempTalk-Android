package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.difft.android.chat.R
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.websocket.api.messages.TTNotifyMessage

abstract class NotifyContentAdapter : MessageContentAdapter() {
    class NotifyContentViewHolder(val textViewContent: TextView, val textViewAction: TextView) : MessageContentAdapter.ContentViewHolder()

    override val layoutRes: Int = R.layout.chat_item_content_notify

    @SuppressLint("SetTextI18n")
    override fun onBindContentView(message: ChatMessage, viewHolder: ContentViewHolder) {
        val textChatMessage = message as NotifyChatMessage
        val textContentViewHolder = viewHolder as NotifyContentViewHolder
        textContentViewHolder.textViewAction.visibility = View.GONE
        textContentViewHolder.textViewContent.text = textChatMessage.notifyMessage?.showContent
        when (textChatMessage.notifyMessage?.notifyType) {
            TTNotifyMessage.NOTIFY_MESSAGE_TYPE_ADD_FRIEND -> {
                when (textChatMessage.notifyMessage?.data?.actionType) {
                    TTNotifyMessage.NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST -> {
//                        textContentViewHolder.textViewContent.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_request, textChatMessage.nickname)
                        textContentViewHolder.textViewAction.visibility = View.VISIBLE
                        textContentViewHolder.textViewAction.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_request_accept)
                        textContentViewHolder.textViewAction.setOnClickListener {
                            onContactRequestAcceptClicked(textChatMessage)
                        }
                    }

                    TTNotifyMessage.NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST_DONE -> {
//                        textContentViewHolder.textViewContent.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_added)
                    }

                    TTNotifyMessage.NOTIFY_ACTION_TYPE_ADD_FRIEND_ACCEPT -> {
//                        textContentViewHolder.textViewContent.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_added)
                    }

                    TTNotifyMessage.NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST_SENT -> {
//                        textContentViewHolder.textViewContent.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_request_sent)
                    }

                    else -> {}
                }
            }

            TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL -> {
                when (textChatMessage.notifyMessage?.data?.actionType) {
                    TTNotifyMessage.NOTIFY_ACTION_TYPE_BLOCKED_CHATIVE -> {
//                        textContentViewHolder.textViewContent.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_blocked_tips)
                        textContentViewHolder.textViewAction.visibility = View.VISIBLE
                        textContentViewHolder.textViewAction.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_send_a_request)
                        textContentViewHolder.textViewAction.setOnClickListener {
                            onSendFriendRequestClicked(textChatMessage)
                        }
                    }

                    TTNotifyMessage.NOTIFY_ACTION_TYPE_BLOCKED_WEA -> {
//                        textContentViewHolder.textViewContent.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_blocked_tips)
                        textContentViewHolder.textViewAction.visibility = View.VISIBLE
                        textContentViewHolder.textViewAction.text = textContentViewHolder.textViewAction.context.getString(R.string.official_bot_name)
                        textContentViewHolder.textViewAction.setOnClickListener {
                            ChatActivity.startActivity(textContentViewHolder.textViewAction.context, textContentViewHolder.textViewAction.context.getString(R.string.official_bot_id))
                        }
                    }

                    TTNotifyMessage.NOTIFY_ACTION_TYPE_OFFLINE -> {
//                        textContentViewHolder.textViewContent.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_offline_tips)
                    }

                    TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED -> {
//                        textContentViewHolder.textViewContent.text = textContentViewHolder.textViewAction.context.getString(R.string.contact_account_exception_tips)
                    }

                    else -> {}
                }
            }

            else -> {}
        }
    }

    override fun createViewHolder(viewGroup: ViewGroup): ContentViewHolder {
        return NotifyContentViewHolder(viewGroup.findViewById(R.id.tv_signature), viewGroup.findViewById(R.id.tv_action))
    }

    abstract fun onContactRequestAcceptClicked(chatMessage: NotifyChatMessage)

    abstract fun onSendFriendRequestClicked(chatMessage: NotifyChatMessage)

    abstract fun onPinClick(chatMessage: NotifyChatMessage)
}