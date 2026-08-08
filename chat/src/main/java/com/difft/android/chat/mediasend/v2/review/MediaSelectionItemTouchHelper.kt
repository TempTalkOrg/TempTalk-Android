package com.difft.android.chat.mediasend.v2.review

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.mediasend.v2.MediaSelectionViewModel

/**
 * A touch helper for handling drag + drop on the media rail in the media send flow.
 */
class MediaSelectionItemTouchHelper(
    private val viewModel: MediaSelectionViewModel
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
        if (viewModel.isValidMediaDragPosition(viewHolder.adapterPosition)) {
            val dragFlags = ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            makeMovementFlags(dragFlags, 0)
        } else {
            0
        }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = viewModel.swapMedia(viewHolder.adapterPosition, target.adapterPosition)

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewModel.onMediaDragFinished()
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
}
