package com.difft.android.chat.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.difft.app.database.convertToContactorModel
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.databinding.LayoutItemGroupMemberBinding
import org.difft.app.database.models.GroupMemberContactorModel


abstract class SearchGroupMemberAdapter : ListAdapter<GroupMemberContactorModel, SearchContactViewHolder>(
    object : DiffUtil.ItemCallback<GroupMemberContactorModel>() {
        override fun areItemsTheSame(oldItem: GroupMemberContactorModel, newItem: GroupMemberContactorModel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: GroupMemberContactorModel, newItem: GroupMemberContactorModel): Boolean =
            oldItem == newItem
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchContactViewHolder {
        return SearchContactViewHolder(parent)
    }

    abstract fun onContactClicked(contact: GroupMemberContactorModel, position: Int)

    override fun onBindViewHolder(holder: SearchContactViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data) {
            onContactClicked(data, position)
        }
    }
}

class SearchContactViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    LayoutItemGroupMemberBinding.inflate(inflater, parentView, false).root
}) {

    private var binding: LayoutItemGroupMemberBinding = LayoutItemGroupMemberBinding.bind(itemView)

    fun bind(data: GroupMemberContactorModel, onItemClickListener: View.OnClickListener) {
        val contactor = data.convertToContactorModel()
        binding.avatarView.setAvatar(contactor)
        binding.tvName.text = contactor.getDisplayNameForUI()

        binding.root.setOnClickListener(onItemClickListener)
    }

}