package com.difft.android.chat.util.adapter.mapping

interface MappingModel<T> {
    fun areItemsTheSame(newItem: T): Boolean
    fun areContentsTheSame(newItem: T): Boolean

    fun getChangePayload(newItem: T): Any? = null
}
