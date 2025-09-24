package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.databinding.ItemEmojiReactionContactBinding
import org.difft.app.database.models.ContactorModel

abstract class EmojiReactionContactorsAdapter : ListAdapter<ContactorModel, EmojiReactionContactorsViewHolder>(
    object : DiffUtil.ItemCallback<ContactorModel>() {
        override fun areItemsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem == newItem
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiReactionContactorsViewHolder {
        return EmojiReactionContactorsViewHolder(parent)
    }

    abstract fun onContactClicked(contact: ContactorModel, position: Int)

    override fun onBindViewHolder(holder: EmojiReactionContactorsViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data) {
            onContactClicked(data, position)
        }
    }
}

class EmojiReactionContactorsViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    ItemEmojiReactionContactBinding.inflate(inflater, parentView, false).root
}) {

    private var binding: ItemEmojiReactionContactBinding = ItemEmojiReactionContactBinding.bind(itemView)

    fun bind(data: ContactorModel, onItemClickListener: View.OnClickListener) {

        binding.avatarView.setAvatar(data)
        binding.tvName.text = data.getDisplayNameForUI()

        binding.tvInfo1.visibility = View.GONE

        binding.root.setOnClickListener(onItemClickListener)
    }

}