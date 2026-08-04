package com.difft.android.selector.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.selector.R
import com.difft.android.selector.adapter.holder.BaseRecyclerMediaHolder
import com.difft.android.selector.config.InjectResourceSource
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMedia

class PictureImageGridAdapter(
    private val mContext: Context,
    private val mConfig: SelectorConfig
) : RecyclerView.Adapter<BaseRecyclerMediaHolder>() {

    private var isDisplayCamera = false

    private var mData = ArrayList<LocalMedia>()

    private var listener: OnItemClickListener? = null

    fun notifyItemPositionChanged(position: Int) {
        notifyItemChanged(position)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setDataAndDataSetChanged(result: ArrayList<LocalMedia>?) {
        if (result != null) {
            mData = result
            notifyDataSetChanged()
        }
    }

    fun isDisplayCamera(): Boolean = isDisplayCamera

    fun setDisplayCamera(displayCamera: Boolean) {
        isDisplayCamera = displayCamera
    }

    fun getData(): ArrayList<LocalMedia> = mData

    fun isDataEmpty(): Boolean = mData.size == 0

    override fun getItemViewType(position: Int): Int {
        if (isDisplayCamera && position == 0) {
            return ADAPTER_TYPE_CAMERA
        } else {
            val adapterPosition = if (isDisplayCamera) position - 1 else position
            val mimeType = mData[adapterPosition].mimeType
            if (PictureMimeType.isHasVideo(mimeType)) {
                return ADAPTER_TYPE_VIDEO
            }
            return ADAPTER_TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseRecyclerMediaHolder {
        return BaseRecyclerMediaHolder.generate(parent, viewType, getItemResourceId(viewType), mConfig)
    }

    private fun getItemResourceId(viewType: Int): Int {
        val layoutResourceId: Int
        when (viewType) {
            ADAPTER_TYPE_CAMERA -> return R.layout.ps_item_grid_camera
            ADAPTER_TYPE_VIDEO -> {
                layoutResourceId = InjectResourceSource.getLayoutResource(mContext, InjectResourceSource.MAIN_ITEM_VIDEO_LAYOUT_RESOURCE, mConfig)
                return if (layoutResourceId != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) layoutResourceId else R.layout.ps_item_grid_video
            }
            else -> {
                layoutResourceId = InjectResourceSource.getLayoutResource(mContext, InjectResourceSource.MAIN_ITEM_IMAGE_LAYOUT_RESOURCE, mConfig)
                return if (layoutResourceId != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) layoutResourceId else R.layout.ps_item_grid_image
            }
        }
    }

    override fun onBindViewHolder(holder: BaseRecyclerMediaHolder, position: Int) {
        if (getItemViewType(position) == ADAPTER_TYPE_CAMERA) {
            holder.itemView.setOnClickListener {
                listener?.openCameraClick()
            }
        } else {
            val adapterPosition = if (isDisplayCamera) position - 1 else position
            val media = mData[adapterPosition]
            holder.bindData(media, adapterPosition)
            holder.setOnItemClickListener(listener)
        }
    }

    override fun getItemCount(): Int {
        return if (isDisplayCamera) mData.size + 1 else mData.size
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    interface OnItemClickListener {
        fun openCameraClick()

        fun onItemClick(selectedView: View, position: Int, media: LocalMedia)

        fun onItemLongClick(itemView: View, position: Int)

        fun onSelected(selectedView: View, position: Int, media: LocalMedia): Int
    }

    companion object {
        const val ADAPTER_TYPE_CAMERA = 1
        const val ADAPTER_TYPE_IMAGE = 2
        const val ADAPTER_TYPE_VIDEO = 3
    }
}
