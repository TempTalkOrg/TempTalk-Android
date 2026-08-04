package com.difft.android.chat.util.adapter.mapping

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import java.util.function.Function

class LayoutFactory<T : MappingModel<T>>(
    private val creator: Function<View, MappingViewHolder<T>>,
    @param:LayoutRes private val layout: Int
) : Factory<T> {

    override fun createViewHolder(parent: ViewGroup): MappingViewHolder<T> =
        creator.apply(LayoutInflater.from(parent.context).inflate(layout, parent, false))
}
