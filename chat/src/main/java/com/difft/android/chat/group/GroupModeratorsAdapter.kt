package com.difft.android.chat.group

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.databinding.LayoutItemGroupModeratorBinding
import org.difft.app.database.models.ContactorModel

@SuppressLint("NotifyDataSetChanged")
class GroupModeratorsAdapter(
    private val onSelectionChanged: ((Set<String>) -> Unit)? = null
) : ListAdapter<ContactorModel, GroupModeratorsViewHolder>(
    object : DiffUtil.ItemCallback<ContactorModel>() {
        override fun areItemsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem == newItem
    }) {

    private val selectedIds = mutableSetOf<String>()

    var selectionMode: SelectionMode = SelectionMode.MULTIPLE
        set(value) {
            field = value
            if (value == SelectionMode.SINGLE && selectedIds.size > 1) {
                val firstSelected = selectedIds.first()
                selectedIds.clear()
                selectedIds.add(firstSelected)
                notifyDataSetChanged()
            }
        }

    fun clearSelections() {
        selectedIds.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupModeratorsViewHolder {
        return GroupModeratorsViewHolder(parent)
    }

    override fun onBindViewHolder(holder: GroupModeratorsViewHolder, position: Int) {
        val data = getItem(position)

        val isSelected = selectedIds.contains(data.id)

        holder.bind(data, isSelected) { contactId ->
            toggleSelection(contactId)
        }
    }

    private fun toggleSelection(contactId: String) {
        when (selectionMode) {
            SelectionMode.SINGLE -> {
                selectedIds.clear()
                if (!selectedIds.contains(contactId)) {
                    selectedIds.add(contactId)
                }
                notifyDataSetChanged()
            }

            SelectionMode.MULTIPLE -> {
                if (selectedIds.contains(contactId)) {
                    selectedIds.remove(contactId)
                } else {
                    selectedIds.add(contactId)
                }
                notifyItemChanged(currentList.indexOfFirst { it.id == contactId })
            }
        }
        onSelectionChanged?.invoke(selectedIds)
    }
}

class GroupModeratorsViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    LayoutItemGroupModeratorBinding.inflate(inflater, parentView, false).root
}) {

    private var binding: LayoutItemGroupModeratorBinding = LayoutItemGroupModeratorBinding.bind(itemView)

    fun bind(data: ContactorModel, isSelected: Boolean, onItemClicked: (String) -> Unit) {
        binding.avatarView.setAvatar(data)
        binding.tvName.text = data.getDisplayNameForUI()

        binding.cbSelect.isChecked = isSelected

        binding.root.setOnClickListener { onItemClicked(data.id) }

        binding.cbSelect.setOnClickListener { onItemClicked(data.id) }
    }
}

enum class SelectionMode {
    SINGLE, MULTIPLE
}