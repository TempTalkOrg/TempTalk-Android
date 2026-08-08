package com.difft.android.selector.animators

import android.animation.Animator
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView

abstract class BaseAnimationAdapter(adapter: RecyclerView.Adapter<*>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    @Suppress("UNCHECKED_CAST")
    private val mAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder> =
        adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>

    private val mDuration = 250
    private val mInterpolator: Interpolator = LinearInterpolator()
    private var mLastPosition = -1
    private val isFirstOnly = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return mAdapter.onCreateViewHolder(parent, viewType)
    }

    override fun registerAdapterDataObserver(observer: RecyclerView.AdapterDataObserver) {
        super.registerAdapterDataObserver(observer)
        mAdapter.registerAdapterDataObserver(observer)
    }

    override fun unregisterAdapterDataObserver(observer: RecyclerView.AdapterDataObserver) {
        super.unregisterAdapterDataObserver(observer)
        mAdapter.unregisterAdapterDataObserver(observer)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        mAdapter.onAttachedToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        mAdapter.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        mAdapter.onViewAttachedToWindow(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        mAdapter.onViewDetachedFromWindow(holder)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        mAdapter.onBindViewHolder(holder, position)

        val adapterPosition = holder.adapterPosition
        if (!isFirstOnly || adapterPosition > mLastPosition) {
            for (anim in getAnimators(holder.itemView)) {
                anim.setDuration(mDuration.toLong()).start()
                anim.interpolator = mInterpolator
            }
            mLastPosition = adapterPosition
        } else {
            ViewHelper.clear(holder.itemView)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        mAdapter.onViewRecycled(holder)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = mAdapter.itemCount

    protected abstract fun getAnimators(view: View): Array<Animator>

    override fun getItemViewType(position: Int): Int = mAdapter.getItemViewType(position)

    override fun getItemId(position: Int): Long = mAdapter.getItemId(position)
}
