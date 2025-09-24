package com.difft.android.chat.group

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

abstract class GroupMembersAdapter(private val showType: Int) : ListAdapter<GroupMemberModel, GroupMemberItemViewHolder>(
    object : DiffUtil.ItemCallback<GroupMemberModel>() {
        override fun areItemsTheSame(oldItem: GroupMemberModel, newItem: GroupMemberModel): Boolean =
            oldItem.uid == newItem.uid

        override fun areContentsTheSame(oldItem: GroupMemberModel, newItem: GroupMemberModel): Boolean =
            oldItem == newItem
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupMemberItemViewHolder {
        return GroupMemberItemViewHolder(parent)
    }

    abstract fun onMemberClicked(model: GroupMemberModel, position: Int)
    open fun onCheckBoxClicked(model: GroupMemberModel, position: Int) {}

    override fun onBindViewHolder(holder: GroupMemberItemViewHolder, position: Int) {
        val data = getItem(position)
        holder.name = data.name
        if (holder.name.isNullOrEmpty()) {
            holder.name = data.uid
        }
        holder.showCheckBox = data.showCheckBox
        holder.checkBoxEnable = data.checkBoxEnable
        holder.isSelected = data.isSelected
        holder.setAvatarUrl(data.avatarUrl, data.avatarEncKey, data.sortLetters, data.uid!!)
        holder.checkBox.setOnClickListener {
            val actualPosition = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
            onCheckBoxClicked(data, actualPosition)
        }

        holder.rootView.setOnClickListener {
            if (!data.checkBoxEnable) return@setOnClickListener
            val actualPosition = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
            onCheckBoxClicked(data, actualPosition)
        }


        holder.avatarView.setOnClickListener {
            onMemberClicked(data, position)
        }
    }
}

data class GroupMemberModel(
    var name: String?,
    var uid: String?,
    var avatarUrl: String?,
    var avatarEncKey: String?,
    var sortLetters: String?,
    var role: Int = GROUP_ROLE_MEMBER,
    var isSelected: Boolean = false,
    var checkBoxEnable: Boolean = true,
    var showCheckBox: Boolean = true,
    var email: String? = null,
    var roleInString: String? = null,
    var going: String? = null,
    var isGroupUser: Boolean? = false,
    var isRemovable: Boolean? = true,
)
