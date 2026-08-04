package com.difft.android.selector.decoration

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L

/**
 * Guards against RecyclerView "IndexOutOfBoundsException: Inconsistency detected.
 * Invalid view holder adapter" during layout.
 */
open class WrapContentLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            L.w(e) { "[WrapContentLinearLayoutManager] onLayoutChildren error:" }
        }
    }
}
