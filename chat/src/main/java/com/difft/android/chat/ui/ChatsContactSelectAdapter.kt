package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.databinding.ChatItemSelectChatsContactBinding
import com.difft.android.chat.group.getAvatarData

abstract class ChatsContactSelectAdapter(private val showMeOriginal: Boolean = false) : ListAdapter<ChatsContact, ChatsContactItemViewHolder>(
    object : DiffUtil.ItemCallback<ChatsContact>() {
        override fun areItemsTheSame(oldItem: ChatsContact, newItem: ChatsContact): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatsContact, newItem: ChatsContact): Boolean =
            oldItem == newItem
    }) {

    private val myID by lazy {
        globalServices.myId
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatsContactItemViewHolder {
        return ChatsContactItemViewHolder(parent, myID, showMeOriginal)
    }

    abstract fun onItemClicked(data: ChatsContact?, position: Int)

    override fun onBindViewHolder(holder: ChatsContactItemViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data) {
            onItemClicked(data, position)
        }
    }
}

class ChatsContactItemViewHolder(val parentView: ViewGroup, val myID: String, private val showMeOriginal: Boolean) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    ChatItemSelectChatsContactBinding.inflate(inflater, parentView, false).root
}) {
    private var binding: ChatItemSelectChatsContactBinding = ChatItemSelectChatsContactBinding.bind(itemView)

    fun bind(data: ChatsContact, onItemClickListener: View.OnClickListener) {

        binding.imageviewAvatar.visibility = View.GONE
        binding.groupAvatar.visibility = View.GONE

        if (data.isGroup) {
            binding.groupAvatar.visibility = View.VISIBLE
            binding.groupAvatar.setAvatar(data.avatarJson?.getAvatarData())
            binding.textviewLabel.text = data.name
        } else {
            binding.imageviewAvatar.visibility = View.VISIBLE
            if (data.id == myID && !showMeOriginal) {
                binding.textviewLabel.text = parentView.context.getString(R.string.chat_favorites)
                binding.imageviewAvatar.showFavorites()
            } else {
                binding.textviewLabel.text = data.name
                val contactAvatar = data.avatar?.getContactAvatarData()
                binding.imageviewAvatar.setAvatar(contactAvatar?.getContactAvatarUrl(), contactAvatar?.encKey, data.firstLetters, data.id)
            }
        }
        binding.textviewDetail.visibility = View.GONE

        binding.root.setOnClickListener(onItemClickListener)
    }

}