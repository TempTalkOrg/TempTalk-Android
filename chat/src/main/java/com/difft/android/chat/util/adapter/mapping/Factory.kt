package com.difft.android.chat.util.adapter.mapping

import android.view.ViewGroup

interface Factory<T : MappingModel<T>> {
    fun createViewHolder(parent: ViewGroup): MappingViewHolder<T>
}
