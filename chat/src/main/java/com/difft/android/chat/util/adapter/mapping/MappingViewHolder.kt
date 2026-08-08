package com.difft.android.chat.util.adapter.mapping

import android.content.Context
import android.view.View
import androidx.annotation.IdRes
import androidx.recyclerview.widget.RecyclerView
import java.util.LinkedList

abstract class MappingViewHolder<Model>(itemView: View) : RecyclerView.ViewHolder(itemView) {

    // Public so both subclass field-style access (`context`) and external getContext() resolve to one member.
    val context: Context = itemView.context
    protected val payload: MutableList<Any> = LinkedList()

    fun <T : View> findViewById(@IdRes id: Int): T = itemView.findViewById(id)

    open fun onAttachedToWindow() {}

    open fun onDetachedFromWindow() {}

    abstract fun bind(model: Model)

    fun setPayload(payload: List<Any>) {
        this.payload.clear()
        this.payload.addAll(payload)
    }

    class SimpleViewHolder<Model>(itemView: View) : MappingViewHolder<Model>(itemView) {
        override fun bind(model: Model) {}
    }
}
