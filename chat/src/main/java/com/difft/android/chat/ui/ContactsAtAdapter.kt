package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutAtListHeaderBinding
import com.difft.android.chat.databinding.LayoutItemContactAtBinding
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import org.difft.app.database.models.ContactorModel

abstract class ContactsAtAdapter : ListAdapter<ContactsAtAdapter.Item, RecyclerView.ViewHolder>(
    object : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
            oldItem == newItem
    }
) {

    companion object {
        const val VIEW_TYPE_TITLE = 0
        const val VIEW_TYPE_CONTACT = 1
    }

    sealed class Item(val id: String) {
        data class Title(val name: String, val content: String) : Item(name)
        data class Contact(val contactor: ContactorModel) : Item(contactor.id)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Item.Title -> VIEW_TYPE_TITLE
            is Item.Contact -> VIEW_TYPE_CONTACT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_TITLE -> {
                val inflater = LayoutInflater.from(parent.context)
                val binding = LayoutAtListHeaderBinding.inflate(inflater, parent, false)
                TitleViewHolder(binding)
            }

            VIEW_TYPE_CONTACT -> {
                SearchContactViewHolder(parent)
            }

            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Title -> (holder as TitleViewHolder).bind(item.name, item.content)
            is Item.Contact -> {
                val contact = item.contactor
                (holder as SearchContactViewHolder).bind(contact) {
                    onContactClicked(contact, position)
                }
            }
        }
    }

    abstract fun onContactClicked(contact: ContactorModel, position: Int)

    class TitleViewHolder(private val binding: LayoutAtListHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(name: String, content: String) {
            binding.tvName.text = name
            binding.tvContent.text = content
        }
    }

    class SearchContactViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
        val inflater = LayoutInflater.from(parentView.context)
        LayoutItemContactAtBinding.inflate(inflater, parentView, false).root
    }) {
        private var binding: LayoutItemContactAtBinding = LayoutItemContactAtBinding.bind(itemView)

        fun bind(data: ContactorModel, onItemClickListener: View.OnClickListener) {
            if (data.id == MENTIONS_ALL_ID) {
                binding.avatarView.setAvatar(R.drawable.chat_icon_at)
            } else {
                binding.avatarView.setAvatar(data, letterTextSizeDp = 14)
            }
            binding.tvName.text = data.getDisplayNameForUI()
            binding.root.setOnClickListener(onItemClickListener)
        }
    }
}
