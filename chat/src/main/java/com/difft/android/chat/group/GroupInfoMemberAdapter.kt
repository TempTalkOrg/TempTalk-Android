package com.difft.android.chat.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.databinding.LayoutGroupMemberBinding

abstract class GroupInfoMemberAdapter : ListAdapter<GroupMemberModel, GroupInfoMemberViewHolder>(
    object : DiffUtil.ItemCallback<GroupMemberModel>() {
        override fun areItemsTheSame(oldItem: GroupMemberModel, newItem: GroupMemberModel): Boolean = oldItem.uid == newItem.uid

        override fun areContentsTheSame(oldItem: GroupMemberModel, newItem: GroupMemberModel): Boolean = oldItem == newItem
    }) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupInfoMemberViewHolder {
        return GroupInfoMemberViewHolder(parent)
    }

//    companion object {
//        const val VIEW_TYPE_MEMBER = 1
//        const val VIEW_TYPE_ADD = 2
//        const val VIEW_TYPE_REMOVE = 3
//    }
//

    override fun onBindViewHolder(holder: GroupInfoMemberViewHolder, position: Int) {
        val data = getItem(position)

        holder.bind(data)
        holder.itemView.setOnClickListener {
            onItemClick(data)
        }
    }

    abstract fun onItemClick(contact: GroupMemberModel)
}

class GroupInfoMemberViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val context = parentView.context
    val layoutInflater = LayoutInflater.from(context)
    val binding = LayoutGroupMemberBinding.inflate(layoutInflater, parentView, false)
    binding.root
}) {
    private val binding = LayoutGroupMemberBinding.bind(itemView)

    fun bind(contactor: GroupMemberModel) {
        binding.ivAdd.visibility = View.GONE
        binding.ivRemove.visibility = View.GONE
        binding.tvName.visibility = View.GONE
        binding.avatar.visibility = View.GONE
        when (contactor.uid) {
            "+" -> {
                binding.ivAdd.visibility = View.VISIBLE
            }

            "-" -> {
                binding.ivRemove.visibility = View.VISIBLE
            }

            else -> {
                binding.tvName.visibility = View.VISIBLE
                binding.avatar.visibility = View.VISIBLE
                binding.tvName.text = contactor.name
                binding.avatar.setAvatar(contactor.avatarUrl, contactor.avatarEncKey, ContactorUtil.getFirstLetter(contactor.letterName ?: contactor.name), contactor.uid ?: "")
            }
        }

    }
}