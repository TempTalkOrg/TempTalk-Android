package com.difft.android.chat.contacts.contactsall

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.core.content.ContextCompat
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.R
import com.difft.android.base.utils.weakcontact.WeakContactCountdown
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.contacts.data.isOfficialBotId
import org.difft.app.database.models.ContactorModel

/**
 * List item wrapper carrying the contactor plus its weak-pending expireAt (uid -> absolute expire
 * ms UTC, or null when the uid is a real friend). Kept as a wrapper instead of polluting
 * [ContactorModel] so the model stays unchanged. Because [expireAt] is part of the data class,
 * DiffUtil's [areContentsTheSame] (`oldItem == newItem`) detects a weak-state change (e.g. friend
 * restored: expireAt has -> null) and rebinds the row, so the countdown subtitle appears/disappears
 * without a manual refresh.
 */
data class ContactListItem(val contactor: ContactorModel, val expireAt: Long?)

abstract class ContactorsAdapter(private val myID: String) : ListAdapter<ContactListItem, ContactItemViewHolder>(
    object : DiffUtil.ItemCallback<ContactListItem>() {
        override fun areItemsTheSame(oldItem: ContactListItem, newItem: ContactListItem): Boolean =
            oldItem.contactor.id == newItem.contactor.id

        override fun areContentsTheSame(oldItem: ContactListItem, newItem: ContactListItem): Boolean =
            oldItem == newItem   // data class equals covers contactor + expireAt
    }) {

    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val oldId = field
            field = value
            currentList.forEachIndexed { index, item ->
                if (item.contactor.id == oldId || item.contactor.id == value) {
                    notifyItemChanged(index)
                }
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactItemViewHolder {
        return ContactItemViewHolder(parent)
    }

    abstract fun onContactClicked(contact: ContactorModel, position: Int)

    override fun onBindViewHolder(holder: ContactItemViewHolder, position: Int) {
        val item = getItem(position)
        val data = item.contactor
        if (data.id == myID) {
            holder.showFavorites()
            holder.name = holder.itemView.context.getString(R.string.chat_favorites)
            holder.setBotBadgeVisible(false)
        } else {
            holder.setAvatarUrl(data)
            holder.name = data.getDisplayNameForUI()
            holder.setBotBadgeVisible(data.id.isOfficialBotId())
        }
        // Weak-pending contacts show a "Removes in N days" countdown subtitle. expireAt rides on the
        // item (part of DiffUtil contents) so it appears/disappears as the weak state changes.
        holder.content = item.expireAt?.let { expireAt ->
            val days = WeakContactCountdown.daysLeftFromClock(expireAt)
            holder.itemView.context.getString(com.difft.android.chat.R.string.weak_contact_remove_in_days, days)
        }
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
            if (currentList[i].contactor.getDisplayNameForUI().getSortLetter() == letter) {
                return i
            }
        }
        return -1
    }
}