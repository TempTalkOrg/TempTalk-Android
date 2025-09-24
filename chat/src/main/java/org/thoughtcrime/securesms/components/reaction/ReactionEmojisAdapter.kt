package org.thoughtcrime.securesms.components.reaction

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutItemReactionEmojiBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage

abstract class ReactionEmojisAdapter(val chatMessage: TextChatMessage) : ListAdapter<String, ReactionEmojisViewHolder>(
    object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem
    }) {

    private val myID: String by lazy {
        globalServices.myId
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReactionEmojisViewHolder {
        return ReactionEmojisViewHolder(parent, chatMessage)
    }

    abstract fun onEmojiSelected(emoji: String, position: Int, remove: Boolean)

    override fun onBindViewHolder(holder: ReactionEmojisViewHolder, position: Int) {
        val emoji = getItem(position)
        val isSelected = chatMessage.reactions?.find { it.emoji == emoji && it.uid == myID } != null
        holder.bind(emoji, isSelected) {
            onEmojiSelected(emoji, position, isSelected)
        }
    }
}

class ReactionEmojisViewHolder(parentView: ViewGroup, val chatMessage: ChatMessage) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    LayoutItemReactionEmojiBinding.inflate(inflater, parentView, false).root
}) {
    private var binding: LayoutItemReactionEmojiBinding = LayoutItemReactionEmojiBinding.bind(itemView)

    fun bind(data: String, isSelected: Boolean, onItemClickListener: View.OnClickListener) {
        if (data == "...") {
            binding.imageviewMenuMore.visibility = View.VISIBLE
            binding.tvEmoji.visibility = View.GONE
        } else {
            binding.imageviewMenuMore.visibility = View.GONE
            binding.tvEmoji.visibility = View.VISIBLE

            if (isSelected) {
                binding.tvEmoji.setBackgroundResource(R.drawable.reactions_old_background)
            } else {
                binding.tvEmoji.setBackgroundResource(0)
            }
            binding.tvEmoji.text = data
        }
        binding.root.setOnClickListener(onItemClickListener)
    }

}