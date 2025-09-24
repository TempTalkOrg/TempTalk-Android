package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View.OnClickListener
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.difft.android.chat.databinding.ChatItemSelectContactsBinding

class ContactSelectsItemViewHolder(parentView: ViewGroup) : ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    ChatItemSelectContactsBinding.inflate(inflater, parentView, false)
        .root
}) {
    private var binding: ChatItemSelectContactsBinding = ChatItemSelectContactsBinding.bind(itemView)

    var name: CharSequence?
        get() = binding.textViewName.text
        set(value) {
            binding.textViewName.text = value
        }

    var email: CharSequence?
        get() = binding.textViewEmail.text
        set(value) {
            binding.textViewEmail.text = value
        }

//    var isSelected: Boolean
//        get() = binding.selectMark.isVisible
//        set(value) {
//            binding.selectMark.isVisible = value
//        }

    fun setAvatarUrl(url: String?, key: String?, firstLetter: String?, id: String) {
        binding.avatarView.setAvatar(url, key, firstLetter, id)
    }

    fun setOnItemClickListener(onClickListener: OnClickListener) {
        binding.root.setOnClickListener(onClickListener)
    }

}