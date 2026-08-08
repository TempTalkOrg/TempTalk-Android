package com.difft.android.selector.adapter

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.selector.R
import com.difft.android.selector.adapter.holder.BasePreviewHolder
import com.difft.android.selector.adapter.holder.PreviewVideoHolder
import com.difft.android.selector.config.InjectResourceSource
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.entity.LocalMedia

class PicturePreviewAdapter(private val selectorConfig: SelectorConfig) :
    RecyclerView.Adapter<BasePreviewHolder>() {

    private var mData: List<LocalMedia>? = null
    private var onPreviewEventListener: BasePreviewHolder.OnPreviewEventListener? = null
    private val mHolderCache = LinkedHashMap<Int, BasePreviewHolder>()

    constructor() : this(SelectorProviders.getInstance().selectorConfig)

    fun getCurrentHolder(position: Int): BasePreviewHolder? {
        return mHolderCache[position]
    }

    fun setData(list: List<LocalMedia>) {
        this.mData = list
    }

    fun setOnPreviewEventListener(listener: BasePreviewHolder.OnPreviewEventListener) {
        this.onPreviewEventListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BasePreviewHolder {
        val layoutResourceId: Int
        if (viewType == BasePreviewHolder.ADAPTER_TYPE_VIDEO) {
            layoutResourceId = InjectResourceSource.getLayoutResource(parent.context, InjectResourceSource.PREVIEW_ITEM_VIDEO_LAYOUT_RESOURCE, selectorConfig)
            return BasePreviewHolder.generate(parent, viewType, if (layoutResourceId != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) layoutResourceId else R.layout.ps_preview_video)
        } else {
            layoutResourceId = InjectResourceSource.getLayoutResource(parent.context, InjectResourceSource.PREVIEW_ITEM_IMAGE_LAYOUT_RESOURCE, selectorConfig)
            return BasePreviewHolder.generate(parent, viewType, if (layoutResourceId != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) layoutResourceId else R.layout.ps_preview_image)
        }
    }

    override fun onBindViewHolder(holder: BasePreviewHolder, position: Int) {
        holder.setOnPreviewEventListener(onPreviewEventListener)
        val media = getItem(position)
        mHolderCache[position] = holder
        holder.bindData(media!!, position)
    }

    fun getItem(position: Int): LocalMedia? {
        if (position > mData!!.size) {
            return null
        }
        return mData!![position]
    }

    override fun getItemViewType(position: Int): Int {
        return if (PictureMimeType.isHasVideo(mData!![position].mimeType)) {
            BasePreviewHolder.ADAPTER_TYPE_VIDEO
        } else {
            BasePreviewHolder.ADAPTER_TYPE_IMAGE
        }
    }

    override fun getItemCount(): Int {
        return mData?.size ?: 0
    }

    override fun onViewAttachedToWindow(holder: BasePreviewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.onViewAttachedToWindow()
    }

    override fun onViewDetachedFromWindow(holder: BasePreviewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.onViewDetachedFromWindow()
    }

    fun setCoverScaleType(position: Int) {
        val currentHolder = getCurrentHolder(position)
        if (currentHolder != null) {
            val media = getItem(position)!!
            if (media.width == 0 && media.height == 0) {
                currentHolder.coverImageView!!.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                currentHolder.coverImageView!!.scaleType = ImageView.ScaleType.CENTER_CROP
            }
        }
    }

    fun setVideoPlayButtonUI(position: Int) {
        val currentHolder = getCurrentHolder(position)
        if (currentHolder is PreviewVideoHolder) {
            if (!currentHolder.isPlaying()) {
                currentHolder.ivPlayButton!!.visibility = View.VISIBLE
            }
        }
    }

    fun startAutoVideoPlay(position: Int) {
        val currentHolder = getCurrentHolder(position)
        if (currentHolder is PreviewVideoHolder) {
            currentHolder.startPlay()
        }
    }

    fun isPlaying(position: Int): Boolean {
        val currentHolder = getCurrentHolder(position)
        return currentHolder != null && currentHolder.isPlaying()
    }

    fun destroy() {
        for (key in mHolderCache.keys) {
            val holder = mHolderCache[key]
            holder?.release()
        }
    }
}
