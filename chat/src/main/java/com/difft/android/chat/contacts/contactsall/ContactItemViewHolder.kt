package com.difft.android.chat.contacts.contactsall

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.databinding.ChatItemContactBinding
import org.difft.app.database.models.ContactorModel

class ContactItemViewHolder(parentView: ViewGroup) : ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    ChatItemContactBinding.inflate(inflater, parentView, false)
        .root
}) {
    private var binding: ChatItemContactBinding = ChatItemContactBinding.bind(itemView)

    var name: CharSequence?
        get() = binding.textViewName.text
        set(value) {
            binding.textViewName.text = value
            if (TextSizeUtil.isLager()) {
                binding.textViewName.textSize = 24f
            } else {
                binding.textViewName.textSize = 16f
            }
        }

    var content: CharSequence?
        get() = binding.textViewContent.text
        set(value) {
            if (TextUtils.isEmpty(value)) {
                binding.textViewContent.visibility = View.GONE
            } else {
                binding.textViewContent.visibility = View.VISIBLE
                binding.textViewContent.text = value
            }
        }

    var isSelected: Boolean
        get() = binding.selectMark.isVisible
        set(value) {
            binding.selectMark.isVisible = value
        }

    fun setAvatarUrl(contactorModel: ContactorModel) {
        binding.avatarView.setAvatar(contactorModel)
    }

    fun showFavorites() {
        binding.avatarView.showFavorites()
    }

    fun setOnAvatarClickListener(onClickListener: OnClickListener) {
//        binding.avatarView.setOnClickListener(onClickListener)
    }

    fun setOnItemClickListener(onClickListener: OnClickListener) {
        binding.root.setOnClickListener(onClickListener)
    }

}