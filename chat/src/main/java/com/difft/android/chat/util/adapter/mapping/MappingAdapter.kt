package com.difft.android.chat.util.adapter.mapping

import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.util.NoCrossfadeChangeDefaultAnimator
import java.util.Optional
import java.util.function.Function

/**
 * A reusable and composable [RecyclerView.Adapter] built on top of [ListAdapter] to provide async
 * item diffing. Model types are mapped to view-holder factories at runtime via [registerFactory].
 */
open class MappingAdapter @JvmOverloads constructor(
    private val useNoCrossfadeAnimator: Boolean = true
) : ListAdapter<MappingModel<*>, MappingViewHolder<*>>(MappingDiffCallback()) {

    private val factories: MutableMap<Int, Factory<*>> = HashMap()
    private val itemTypes: MutableMap<Class<*>, Int> = HashMap()
    private var typeCount = 0

    override fun onViewAttachedToWindow(holder: MappingViewHolder<*>) {
        super.onViewAttachedToWindow(holder)
        holder.onAttachedToWindow()
    }

    override fun onViewDetachedFromWindow(holder: MappingViewHolder<*>) {
        super.onViewDetachedFromWindow(holder)
        holder.onDetachedFromWindow()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (useNoCrossfadeAnimator &&
            recyclerView.itemAnimator != null &&
            recyclerView.itemAnimator!!.javaClass == DefaultItemAnimator::class.java
        ) {
            recyclerView.itemAnimator = NoCrossfadeChangeDefaultAnimator()
        }
    }

    fun <T : MappingModel<T>> registerFactory(clazz: Class<T>, factory: Factory<T>) {
        val type = typeCount++
        factories[type] = factory
        itemTypes[clazz] = type
    }

    fun <T : MappingModel<T>> registerFactory(
        clazz: Class<T>,
        creator: Function<View, MappingViewHolder<T>>,
        @LayoutRes layout: Int
    ) {
        registerFactory(clazz, LayoutFactory(creator, layout))
    }

    fun getItemTypes(): Map<Class<*>, Int> = HashMap(itemTypes)

    override fun getItemViewType(position: Int): Int {
        return itemTypes[getItem(position).javaClass]
            ?: throw AssertionError("No view holder factory for type: " + getItem(position).javaClass)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MappingViewHolder<*> {
        return factories[viewType]!!.createViewHolder(parent)
    }

    override fun onBindViewHolder(holder: MappingViewHolder<*>, position: Int, payloads: MutableList<Any>) {
        holder.setPayload(payloads)
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: MappingViewHolder<*>, position: Int) {
        @Suppress("UNCHECKED_CAST")
        (holder as MappingViewHolder<Any?>).bind(getItem(position))
    }

    fun <T> indexOfFirst(clazz: Class<T>, predicate: Function1<T, Boolean>): Int {
        return currentList.indexOfFirst { m ->
            @Suppress("UNCHECKED_CAST")
            clazz.isAssignableFrom(m.javaClass) && predicate.invoke(m as T)
        }
    }

    fun getModel(index: Int): Optional<MappingModel<*>> {
        val currentList = currentList
        return if (index in currentList.indices) {
            Optional.ofNullable(currentList[index])
        } else {
            Optional.empty()
        }
    }
}
