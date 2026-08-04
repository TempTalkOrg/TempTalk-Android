package com.difft.android.selector.adapter.holder

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.selector.R
import com.difft.android.selector.config.InjectResourceSource
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.utils.StyleUtils

class PreviewGalleryAdapter(
    config: SelectorConfig,
    private val isBottomPreview: Boolean
) : RecyclerView.Adapter<PreviewGalleryAdapter.ViewHolder>() {
    private val selectorConfig: SelectorConfig = config
    private val mData: MutableList<LocalMedia> = ArrayList(config.selectedResult)

    init {
        for (i in mData.indices) {
            val media = mData[i]
            media.isGalleryEnabledMask = false
            media.isChecked = false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutResourceId = InjectResourceSource.getLayoutResource(
            parent.context,
            InjectResourceSource.PREVIEW_GALLERY_ITEM_LAYOUT_RESOURCE, selectorConfig
        )
        val itemView = LayoutInflater.from(parent.context).inflate(
            if (layoutResourceId != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) layoutResourceId
            else R.layout.ps_preview_gallery_item, parent, false
        )
        return ViewHolder(itemView)
    }

    fun getData(): List<LocalMedia> = mData

    fun clear() {
        mData.clear()
    }

    fun addGalleryData(currentMedia: LocalMedia) {
        val lastCheckPosition = getLastCheckPosition()
        if (lastCheckPosition != RecyclerView.NO_POSITION) {
            val lastSelectedMedia = mData[lastCheckPosition]
            lastSelectedMedia.isChecked = false
            notifyItemChanged(lastCheckPosition)
        }
        if (isBottomPreview && mData.contains(currentMedia)) {
            val currentPosition = getCurrentPosition(currentMedia)
            val media = mData[currentPosition]
            media.isGalleryEnabledMask = false
            media.isChecked = true
            notifyItemChanged(currentPosition)
        } else {
            currentMedia.isChecked = true
            mData.add(currentMedia)
            notifyItemChanged(mData.size - 1)
        }
    }

    fun removeGalleryData(currentMedia: LocalMedia) {
        val currentPosition = getCurrentPosition(currentMedia)
        if (currentPosition != RecyclerView.NO_POSITION) {
            if (isBottomPreview) {
                val media = mData[currentPosition]
                media.isGalleryEnabledMask = true
                notifyItemChanged(currentPosition)
            } else {
                mData.removeAt(currentPosition)
                notifyItemRemoved(currentPosition)
            }
        }
    }

    fun isSelectMedia(currentMedia: LocalMedia) {
        val lastCheckPosition = getLastCheckPosition()
        if (lastCheckPosition != RecyclerView.NO_POSITION) {
            val lastSelectedMedia = mData[lastCheckPosition]
            lastSelectedMedia.isChecked = false
            notifyItemChanged(lastCheckPosition)
        }

        val currentPosition = getCurrentPosition(currentMedia)
        if (currentPosition != RecyclerView.NO_POSITION) {
            val media = mData[currentPosition]
            media.isChecked = true
            notifyItemChanged(currentPosition)
        }
    }

    fun getLastCheckPosition(): Int {
        for (i in mData.indices) {
            val media = mData[i]
            if (media.isChecked) {
                return i
            }
        }
        return RecyclerView.NO_POSITION
    }

    private fun getCurrentPosition(currentMedia: LocalMedia): Int {
        for (i in mData.indices) {
            val media = mData[i]
            if (TextUtils.equals(media.path, currentMedia.path) || media.id == currentMedia.id) {
                return i
            }
        }
        return RecyclerView.NO_POSITION
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mData[position]
        val colorFilter = StyleUtils.getColorFilter(
            holder.itemView.context,
            if (item.isGalleryEnabledMask) R.color.ps_color_half_white else R.color.ps_color_transparent
        )
        if (item.isChecked && item.isGalleryEnabledMask) {
            holder.viewBorder.visibility = View.VISIBLE
        } else {
            holder.viewBorder.visibility = if (item.isChecked) View.VISIBLE else View.GONE
        }
        var path = item.path
        if (item.isEditorImage && !TextUtils.isEmpty(item.cutPath)) {
            path = item.cutPath ?: path
            holder.ivEditor.visibility = View.VISIBLE
        } else {
            holder.ivEditor.visibility = View.GONE
        }
        holder.ivImage.setColorFilter(colorFilter)
        selectorConfig.imageEngine?.loadGridImage(holder.itemView.context, path, holder.ivImage)
        holder.ivPlay.visibility = if (PictureMimeType.isHasVideo(item.mimeType)) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { view ->
            listener?.onItemClick(holder.absoluteAdapterPosition, item, view)
        }
        holder.itemView.setOnLongClickListener { v ->
            val l = mItemLongClickListener
            if (l != null) {
                val adapterPosition = holder.absoluteAdapterPosition
                l.onItemLongClick(holder, adapterPosition, v)
            }
            true
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivImage: ImageView = itemView.findViewById(R.id.ivImage)
        val ivPlay: ImageView = itemView.findViewById(R.id.ivPlay)
        val ivEditor: ImageView = itemView.findViewById(R.id.ivEditor)
        val viewBorder: View = itemView.findViewById(R.id.viewBorder)

        init {
            val selectMainStyle = selectorConfig.selectorStyle.selectMainStyle!!
            if (StyleUtils.checkStyleValidity(selectMainStyle.adapterImageEditorResources)) {
                ivEditor.setImageResource(selectMainStyle.adapterImageEditorResources)
            }
            if (StyleUtils.checkStyleValidity(selectMainStyle.adapterPreviewGalleryFrameResource)) {
                viewBorder.setBackgroundResource(selectMainStyle.adapterPreviewGalleryFrameResource)
            }

            val adapterPreviewGalleryItemSize = selectMainStyle.adapterPreviewGalleryItemSize
            if (StyleUtils.checkSizeValidity(adapterPreviewGalleryItemSize)) {
                val params = RelativeLayout.LayoutParams(adapterPreviewGalleryItemSize, adapterPreviewGalleryItemSize)
                itemView.layoutParams = params
            }
        }
    }

    override fun getItemCount(): Int = mData.size

    private var listener: OnItemClickListener? = null

    fun setItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int, media: LocalMedia, v: View)
    }

    private var mItemLongClickListener: OnItemLongClickListener? = null

    fun setItemLongClickListener(listener: OnItemLongClickListener?) {
        this.mItemLongClickListener = listener
    }

    interface OnItemLongClickListener {
        fun onItemLongClick(holder: RecyclerView.ViewHolder, position: Int, v: View)
    }
}
