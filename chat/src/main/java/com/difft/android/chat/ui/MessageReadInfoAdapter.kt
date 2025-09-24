package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.databinding.ChatItemMessageReadInfoBinding
import org.difft.app.database.models.ContactorModel

abstract class MessageReadInfoAdapter : ListAdapter<ContactorModel, MessageReadInfoViewHolder>(
    object : DiffUtil.ItemCallback<ContactorModel>() {
        override fun areItemsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem == newItem
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageReadInfoViewHolder {
        return MessageReadInfoViewHolder(parent)
    }

    abstract fun onContactClicked(contact: ContactorModel, position: Int)

    override fun onBindViewHolder(holder: MessageReadInfoViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data) {
            onContactClicked(data, position)
        }
    }
}

class MessageReadInfoViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    ChatItemMessageReadInfoBinding.inflate(inflater, parentView, false).root
}) {
    private var binding: ChatItemMessageReadInfoBinding = ChatItemMessageReadInfoBinding.bind(itemView)

    fun bind(data: ContactorModel, onItemClickListener: View.OnClickListener) {
        binding.avatarView.setAvatar(data)
        binding.tvName.text = data.getDisplayNameForUI()
        binding.root.setOnClickListener(onItemClickListener)
    }

}