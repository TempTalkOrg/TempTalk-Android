package com.difft.android.chat.contacts.contactsgroup

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.databinding.ChatItemGroupBinding
import com.difft.android.chat.group.getAvatarData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.difft.app.database.models.GroupModel

abstract class GroupsAdapter : ListAdapter<GroupModel, GroupItemViewHolder>(object : DiffUtil.ItemCallback<GroupModel>() {
    override fun areItemsTheSame(oldItem: GroupModel, newItem: GroupModel): Boolean =
        oldItem == newItem

    override fun areContentsTheSame(oldItem: GroupModel, newItem: GroupModel): Boolean = oldItem.gid == newItem.gid
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupItemViewHolder =
        GroupItemViewHolder(parent)

    override fun onBindViewHolder(holder: GroupItemViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data)
        holder.itemView.setOnClickListener { onItemClick(data) }
    }

    abstract fun onItemClick(group: GroupModel)
}

class GroupItemViewHolder(parentView: ViewGroup) : ViewHolder(run {
    val context = parentView.context
    val layoutInflater = LayoutInflater.from(context)
    val binding = ChatItemGroupBinding.inflate(layoutInflater, parentView, false)
    binding.root
}) {
    private val binding = ChatItemGroupBinding.bind(itemView)

    fun bind(data: GroupModel) {
        binding.textviewGroupName.textSize = if (TextSizeUtil.isLarger) 24f else 16f
        binding.textviewGroupName.text = data.name
        binding.imageviewGroup.setAvatar(data.avatar?.getAvatarData())
    }
}