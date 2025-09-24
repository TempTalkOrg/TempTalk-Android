package com.difft.android.login.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.login.databinding.LayoutItemCountryCodeBinding

abstract class CountryPickerAdapter : ListAdapter<CountryItem, GroupModeratorsViewHolder>(
    object : DiffUtil.ItemCallback<CountryItem>() {
        override fun areItemsTheSame(oldItem: CountryItem, newItem: CountryItem): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: CountryItem, newItem: CountryItem): Boolean =
            oldItem == newItem
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupModeratorsViewHolder {
        return GroupModeratorsViewHolder(parent)
    }

    override fun onBindViewHolder(holder: GroupModeratorsViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data)
        holder.itemView.setOnClickListener {
            onItemClick(data)
        }
    }

    abstract fun onItemClick(data: CountryItem)
}

class GroupModeratorsViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    LayoutItemCountryCodeBinding.inflate(inflater, parentView, false).root
}) {

    private var binding: LayoutItemCountryCodeBinding = LayoutItemCountryCodeBinding.bind(itemView)

    fun bind(data: CountryItem) {
        binding.tvName.text = data.name
        binding.tvCode.text = data.code
    }
}

data class CountryItem(
    val name: String,
    val code: String,
    val countryNamePinyin: String
)
