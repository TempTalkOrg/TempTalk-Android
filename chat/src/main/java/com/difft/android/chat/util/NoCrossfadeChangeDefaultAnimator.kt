package com.difft.android.chat.util

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Disable animations for changes to same item
 */
class NoCrossfadeChangeDefaultAnimator : DefaultItemAnimator() {

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder?,
        newHolder: RecyclerView.ViewHolder?,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        if (oldHolder === newHolder) {
            if (oldHolder != null) {
                dispatchChangeFinished(oldHolder, true)
            }
        } else {
            if (oldHolder != null) {
                dispatchChangeFinished(oldHolder, true)
            }
            if (newHolder != null) {
                dispatchChangeFinished(newHolder, false)
            }
        }
        return false
    }

    override fun canReuseUpdatedViewHolder(viewHolder: RecyclerView.ViewHolder, payloads: MutableList<Any>): Boolean {
        return true
    }
}
