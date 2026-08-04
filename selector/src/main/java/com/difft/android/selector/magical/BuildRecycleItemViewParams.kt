package com.difft.android.selector.magical

import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

object BuildRecycleItemViewParams {

    private val viewParams: MutableList<ViewParams> = ArrayList()

    @JvmStatic
    fun clear() {
        if (viewParams.size > 0) {
            viewParams.clear()
        }
    }

    @JvmStatic
    fun getItemViewParams(position: Int): ViewParams? {
        return if (viewParams.size > position) viewParams[position] else null
    }

    @JvmStatic
    fun generateViewParams(viewGroup: ViewGroup, statusBarHeight: Int) {
        val views: MutableList<View?> = ArrayList()
        val childCount: Int
        if (viewGroup is RecyclerView) {
            childCount = viewGroup.childCount
        } else if (viewGroup is ListView) {
            childCount = viewGroup.childCount
        } else {
            throw IllegalArgumentException(
                "${viewGroup.javaClass.canonicalName} Must be ${RecyclerView::class.java} or ${ListView::class.java}"
            )
        }
        for (i in 0 until childCount) {
            val view = viewGroup.getChildAt(i) ?: continue
            views.add(view)
        }
        val firstPos: Int
        var lastPos: Int
        val totalCount: Int
        if (viewGroup is RecyclerView) {
            val layoutManager = viewGroup.layoutManager as GridLayoutManager?
            if (layoutManager == null) {
                return
            }
            totalCount = layoutManager.itemCount
            firstPos = layoutManager.findFirstVisibleItemPosition()
            lastPos = layoutManager.findLastVisibleItemPosition()
        } else {
            val listView = viewGroup as ListView
            val listAdapter = listView.adapter
            if (listAdapter == null) {
                return
            }
            totalCount = listAdapter.count
            firstPos = listView.firstVisiblePosition
            lastPos = listView.lastVisiblePosition
        }
        lastPos = if (lastPos > totalCount) totalCount - 1 else lastPos
        fillPlaceHolder(views, totalCount, firstPos, lastPos)
        viewParams.clear()
        for (i in views.indices) {
            val view = views[i]
            val viewParam = ViewParams()
            if (view == null) {
                viewParam.left = 0
                viewParam.top = 0
                viewParam.width = 0
                viewParam.height = 0
            } else {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                viewParam.left = location[0]
                viewParam.top = location[1] - statusBarHeight
                viewParam.width = view.width
                viewParam.height = view.height
            }
            viewParams.add(viewParam)
        }
    }

    private fun fillPlaceHolder(originImageList: MutableList<View?>, totalCount: Int, firstPos: Int, lastPos: Int) {
        if (firstPos > 0) {
            for (i in firstPos downTo 1) {
                originImageList.add(0, null)
            }
        }

        if (lastPos < totalCount) {
            for (i in totalCount - 1 - lastPos downTo 1) {
                originImageList.add(null)
            }
        }
    }
}
