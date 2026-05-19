package com.difft.android.chat.contacts.contactsall

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.core.content.ContextCompat
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.R
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.contacts.data.isOfficialBotId
import org.difft.app.database.models.ContactorModel

abstract class ContactorsAdapter(private val myID: String) : ListAdapter<ContactorModel, ContactItemViewHolder>(
    object : DiffUtil.ItemCallback<ContactorModel>() {
        override fun areItemsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem == newItem
    }) {

    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val oldId = field
            field = value
            currentList.forEachIndexed { index, item ->
                if (item.id == oldId || item.id == value) {
                    notifyItemChanged(index)
                }
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactItemViewHolder {
        return ContactItemViewHolder(parent)
    }

    abstract fun onContactClicked(contact: ContactorModel, position: Int)

    override fun onBindViewHolder(holder: ContactItemViewHolder, position: Int) {
        val data = getItem(position)
        if (data.id == myID) {
            holder.showFavorites()
            holder.name = holder.itemView.context.getString(R.string.chat_favorites)
            holder.setBotBadgeVisible(false)
        } else {
            holder.setAvatarUrl(data)
            holder.name = data.getDisplayNameForUI()
            holder.setBotBadgeVisible(data.id.isOfficialBotId())
        }
        holder.content = null
        val bgColorRes = if (selectedId != null && data.id == selectedId) R.color.bg3 else R.color.bg1
        holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, bgColorRes))
        holder.setOnItemClickListener {
//            startChatActivity(contactorID)
            onContactClicked(data, position)
        }
        holder.setOnAvatarClickListener {
//            startContactorDetailPage(contactorID)
        }
    }

    open fun getLetterPosition(letter: String?): Int {
        for (i in 0 until currentList.size) {
            if (currentList[i].getDisplayNameForUI().getSortLetter() == letter) {
                return i
            }
        }
        return -1
    }
}