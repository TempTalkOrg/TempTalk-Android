package com.difft.android.search

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.difft.android.R
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.group.getAvatarData
import com.difft.android.chat.search.setHighLightText
import com.difft.android.databinding.LayoutItemSearchGroupBinding

abstract class SearchGroupsAdapter : ListAdapter<GroupUIData, SearchGroupItemViewHolder>(object : DiffUtil.ItemCallback<GroupUIData>() {
    override fun areItemsTheSame(oldItem: GroupUIData, newItem: GroupUIData): Boolean =
        oldItem == newItem

    override fun areContentsTheSame(oldItem: GroupUIData, newItem: GroupUIData): Boolean = oldItem.gid == newItem.gid
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchGroupItemViewHolder =
        SearchGroupItemViewHolder(parent)

    override fun onBindViewHolder(holder: SearchGroupItemViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(searchKey, data)
        holder.itemView.setOnClickListener { onItemClick(data) }
    }

    abstract fun onItemClick(group: GroupUIData)

    private var searchKey: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun setOrUpdateSearchKey(key: String) {
        searchKey = key
        notifyDataSetChanged()
    }
}

class SearchGroupItemViewHolder(parentView: ViewGroup) : ViewHolder(run {
    val context = parentView.context
    val layoutInflater = LayoutInflater.from(context)
    val binding = LayoutItemSearchGroupBinding.inflate(layoutInflater, parentView, false)
    binding.root
}) {
    private val binding = LayoutItemSearchGroupBinding.bind(itemView)

    fun bind(searchKey: String, data: GroupUIData) {
        binding.groupAvatar.setAvatar(data.avatar?.getAvatarData())

        binding.textviewGroupName.setHighLightText(data.name, searchKey)

        binding.tvDetail.visibility = View.GONE

        if (data.name?.contains(searchKey, ignoreCase = true) == false) {
            val contentIncludeKey = data.members.firstNotNullOfOrNull { member ->
                when {
                    member.displayName?.contains(searchKey, ignoreCase = true) == true -> member.displayName
                    member.remark?.contains(searchKey, ignoreCase = true) == true -> member.remark
                    else -> null
                }
            }
            if (!contentIncludeKey.isNullOrEmpty()) {
                binding.tvDetail.visibility = View.VISIBLE
                binding.tvDetail.setHighLightText(binding.root.context.getString(R.string.search_result_inclued, contentIncludeKey), searchKey)
            }
        }
    }
}