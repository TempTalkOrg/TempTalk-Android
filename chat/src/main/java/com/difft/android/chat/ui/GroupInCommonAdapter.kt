package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.difft.app.database.members
import com.difft.android.chat.databinding.ChatItemGroupInCommonBinding
import com.difft.android.chat.group.getAvatarData
import org.difft.app.database.models.GroupModel

abstract class GroupInCommonAdapter : ListAdapter<GroupModel, GroupInCommonItemViewHolder>(object : DiffUtil.ItemCallback<GroupModel>() {
    override fun areItemsTheSame(oldItem: GroupModel, newItem: GroupModel): Boolean =
        oldItem == newItem

    override fun areContentsTheSame(oldItem: GroupModel, newItem: GroupModel): Boolean = oldItem.gid == newItem.gid
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupInCommonItemViewHolder =
        GroupInCommonItemViewHolder(parent)

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: GroupInCommonItemViewHolder, position: Int) {
        val data = getItem(position)
        holder.avatar = data.avatar
        holder.groupName = data.name
        val builder = StringBuilder()
        data.members.forEachIndexed { index, groupMemberContactor ->
            if (index < 3) {
                builder.append(groupMemberContactor.displayName)
                builder.append(", ")
            }
        }
        if (builder.length > 2) {
            holder.groupMemberNames = builder.substring(0, builder.length - 2).toString()
        }
        holder.itemView.setOnClickListener { onItemClick(data) }
        var touchX = 0
        var touchY = 0
        holder.itemView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                touchX = event.rawX.toInt()
                touchY = event.rawY.toInt()
            }
            false
        }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(it, data, touchX, touchY)
            true
        }
    }

    abstract fun onItemClick(group: GroupModel)

    abstract fun onItemLongClick(itemView: View, group: GroupModel, touchX: Int, touchY: Int)
}

class GroupInCommonItemViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val context = parentView.context
    val layoutInflater = LayoutInflater.from(context)
    val binding = ChatItemGroupInCommonBinding.inflate(layoutInflater, parentView, false)
    binding.root
}) {
    private val binding = ChatItemGroupInCommonBinding.bind(itemView)

    var avatar: CharSequence?
        get() = ""
        set(value) {
            binding.imageviewGroup.setAvatar(value.toString().getAvatarData())
        }

    var groupName: CharSequence?
        get() = binding.textviewGroupName.text
        set(value) {
            binding.textviewGroupName.text = value
        }

    var groupMemberNames: CharSequence?
        get() = binding.textviewGroupMembers.text
        set(value) {
            binding.textviewGroupMembers.text = value
        }
}