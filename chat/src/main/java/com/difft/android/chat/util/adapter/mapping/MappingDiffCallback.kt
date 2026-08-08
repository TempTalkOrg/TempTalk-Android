package com.difft.android.chat.util.adapter.mapping

import android.annotation.SuppressLint
import androidx.recyclerview.widget.DiffUtil

internal class MappingDiffCallback : DiffUtil.ItemCallback<MappingModel<*>>() {

    override fun areItemsTheSame(oldItem: MappingModel<*>, newItem: MappingModel<*>): Boolean {
        if (oldItem.javaClass == newItem.javaClass) {
            @Suppress("UNCHECKED_CAST")
            return (oldItem as MappingModel<Any?>).areItemsTheSame(newItem)
        }
        return false
    }

    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(oldItem: MappingModel<*>, newItem: MappingModel<*>): Boolean {
        if (oldItem.javaClass == newItem.javaClass) {
            @Suppress("UNCHECKED_CAST")
            return (oldItem as MappingModel<Any?>).areContentsTheSame(newItem)
        }
        return false
    }

    override fun getChangePayload(oldItem: MappingModel<*>, newItem: MappingModel<*>): Any? {
        if (oldItem.javaClass == newItem.javaClass) {
            @Suppress("UNCHECKED_CAST")
            return (oldItem as MappingModel<Any?>).getChangePayload(newItem)
        }
        return null
    }
}
