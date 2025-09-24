package com.difft.android.setting.language

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.LanguageData
import com.difft.android.databinding.LayoutLanguageItemBinding

abstract class LanguageAdapter() : ListAdapter<LanguageData, LanguageViewHolder>(
    object : DiffUtil.ItemCallback<LanguageData>() {
        override fun areItemsTheSame(oldItem: LanguageData, newItem: LanguageData): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: LanguageData, newItem: LanguageData): Boolean =
            oldItem == newItem
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        return LanguageViewHolder(parent)
    }

    abstract fun onItemClicked(languageData: LanguageData, position: Int)

    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data) {
            onItemClicked(data, position)
        }
    }
}

class LanguageViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    LayoutLanguageItemBinding.inflate(inflater, parentView, false).root
}) {
    private var binding: LayoutLanguageItemBinding = LayoutLanguageItemBinding.bind(itemView)

    fun bind(data: LanguageData, onItemClickListener: View.OnClickListener) {
        binding.root.setOnClickListener(onItemClickListener)
        binding.tvName.text = data.name
        binding.ivSelected.visibility = if (data.selected) View.VISIBLE else View.GONE
    }

}