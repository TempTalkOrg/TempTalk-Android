package com.difft.android.setting.theme

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.databinding.LayoutLanguageItemBinding

abstract class ThemeAdapter : ListAdapter<ThemeData, ThemeViewHolder>(
    object : DiffUtil.ItemCallback<ThemeData>() {
        override fun areItemsTheSame(oldItem: ThemeData, newItem: ThemeData): Boolean = false

        override fun areContentsTheSame(oldItem: ThemeData, newItem: ThemeData): Boolean = false
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThemeViewHolder {
        return ThemeViewHolder(parent)
    }

    abstract fun onItemClicked(themeData: ThemeData, position: Int)

    override fun onBindViewHolder(holder: ThemeViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data) {
            onItemClicked(data, position)
        }
    }
}

class ThemeViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    LayoutLanguageItemBinding.inflate(inflater, parentView, false).root
}) {
    private var binding: LayoutLanguageItemBinding = LayoutLanguageItemBinding.bind(itemView)

    fun bind(data: ThemeData, onItemClickListener: View.OnClickListener) {
        binding.root.setOnClickListener(onItemClickListener)
        binding.tvName.text = data.themeName
        binding.ivSelected.visibility = if (data.selected) View.VISIBLE else View.GONE
    }
}

data class ThemeData(
    val theme: Int,
    val themeName: String,
    var selected: Boolean
)