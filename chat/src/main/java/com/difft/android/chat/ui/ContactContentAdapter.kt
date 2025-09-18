package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import com.difft.android.base.utils.RxUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isConfidential
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies

open class ContactContentAdapter : MessageContentAdapter() {
    class ContactContentViewHolder(
        val avatarView: AvatarView,
        val tvName: AppCompatTextView,
        val coverView: View
    ) : ContentViewHolder()

    val application = ApplicationDependencies.getApplication()
    override val layoutRes: Int = R.layout.chat_item_content_contact

    @SuppressLint("CheckResult")
    override fun onBindContentView(message: ChatMessage, contentViewHolder: ContentViewHolder) {
        val chatMessage = message as TextChatMessage
        val contacts = chatMessage.sharedContacts
        if (contacts.isNullOrEmpty()) return
        val contact = contacts[0]
        val viewHolder = contentViewHolder as ContactContentViewHolder
        val id = contact.phone?.firstOrNull()?.value ?: ""
        val name = contact.name?.displayName
        viewHolder.tvName.text = name

        viewHolder.avatarView.setAvatar(null, null, ContactorUtil.getFirstLetter(name), id)
        ContactorUtil.getContactWithID(viewHolder.avatarView.context, id)
            .compose(RxUtil.getSingleSchedulerComposer())
            .subscribe({ response ->
                if (response.isPresent) {
                    response.get().let {
                        viewHolder.avatarView.setAvatar(it)
                    }
                }
            }, { it.printStackTrace() })

        if (chatMessage.isConfidential()) {
            contentViewHolder.coverView.visibility = View.VISIBLE
        } else {
            contentViewHolder.coverView.visibility = View.GONE
        }
    }

    override fun createViewHolder(viewGroup: ViewGroup): ContentViewHolder {
        return ContactContentViewHolder(
            viewGroup.findViewById(R.id.imageview_avatar),
            viewGroup.findViewById(R.id.tv_name),
            viewGroup.findViewById(R.id.v_cover),
        )
    }
}