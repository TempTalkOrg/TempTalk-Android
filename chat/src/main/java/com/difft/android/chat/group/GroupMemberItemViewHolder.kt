package com.difft.android.chat.group

import android.view.LayoutInflater
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.databinding.ChatItemGroupMemberBinding

class GroupMemberItemViewHolder(parentView: ViewGroup) : ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    ChatItemGroupMemberBinding.inflate(inflater, parentView, false)
        .root
}) {
    private var binding: ChatItemGroupMemberBinding = ChatItemGroupMemberBinding.bind(itemView)

    var name: CharSequence?
        get() = binding.textViewName.text
        set(value) {
            binding.textViewName.text = value
        }

    var showCheckBox: Boolean
        get() = binding.selectMark.isVisible
        set(value) {
            binding.selectMark.isVisible = value
        }

    var checkBoxEnable: Boolean
        get() = binding.selectMark.isEnabled
        set(value) {
            binding.selectMark.isEnabled = value
        }

    var isSelected: Boolean
        get() = binding.selectMark.isChecked
        set(value) {
            binding.selectMark.isChecked = value
        }

    var checkBox: CheckBox = binding.selectMark

    var rootView: View = binding.root

    var avatarView: AvatarView = binding.avatarView

    fun setAvatarUrl(url: String?, key: String?, firstLetter: String?, id: String) {
        binding.avatarView.setAvatar(url, key, firstLetter, id)
    }
}