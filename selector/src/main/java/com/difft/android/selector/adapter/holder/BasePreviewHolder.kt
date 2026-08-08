package com.difft.android.selector.adapter.holder

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.selector.R
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.photoview.PhotoView
import com.difft.android.selector.utils.BitmapUtils
import com.difft.android.selector.utils.DensityUtil
import com.difft.android.selector.utils.MediaUtils

abstract class BasePreviewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    protected val screenWidth: Int
    protected val screenHeight: Int
    protected val screenAppInHeight: Int
    protected var media: LocalMedia? = null
    protected val selectorConfig: SelectorConfig

    @JvmField
    var coverImageView: PhotoView? = null

    init {
        selectorConfig = SelectorProviders.getInstance().selectorConfig
        screenWidth = DensityUtil.getRealScreenWidth(itemView.context)
        screenHeight = DensityUtil.getScreenHeight(itemView.context)
        screenAppInHeight = DensityUtil.getRealScreenHeight(itemView.context)
        coverImageView = itemView.findViewById(R.id.preview_image)
        findViews(itemView)
    }

    protected abstract fun findViews(itemView: View)

    protected abstract fun loadImage(media: LocalMedia, maxWidth: Int, maxHeight: Int)

    protected abstract fun onClickBackPressed()

    protected abstract fun onLongPressDownload(media: LocalMedia)

    open fun bindData(media: LocalMedia, position: Int) {
        this.media = media
        val size = getRealSizeFromMedia(media)
        val maxImageSize = BitmapUtils.getMaxImageSize(size[0], size[1])
        loadImage(media, maxImageSize[0], maxImageSize[1])
        setScaleDisplaySize(media)
        setCoverScaleType(media)
        onClickBackPressed()
        onLongPressDownload(media)
    }

    protected fun getRealSizeFromMedia(media: LocalMedia): IntArray {
        return if (media.isCut && media.cropImageWidth > 0 && media.cropImageHeight > 0) {
            intArrayOf(media.cropImageWidth, media.cropImageHeight)
        } else {
            intArrayOf(media.width, media.height)
        }
    }

    protected fun setCoverScaleType(media: LocalMedia) {
        if (MediaUtils.isLongImage(media.width, media.height)) {
            coverImageView!!.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            coverImageView!!.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    protected open fun setScaleDisplaySize(media: LocalMedia) {
        if (!selectorConfig.isPreviewZoomEffect && screenWidth < screenHeight) {
            if (media.width > 0 && media.height > 0) {
                val layoutParams = coverImageView!!.layoutParams as FrameLayout.LayoutParams
                layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                layoutParams.gravity = Gravity.CENTER
            }
        }
    }

    open fun onViewAttachedToWindow() {
    }

    open fun onViewDetachedFromWindow() {
    }

    open fun resumePausePlay() {
    }

    open fun isPlaying(): Boolean {
        return false
    }

    open fun release() {
    }

    protected var mPreviewEventListener: OnPreviewEventListener? = null

    fun setOnPreviewEventListener(listener: OnPreviewEventListener?) {
        this.mPreviewEventListener = listener
    }

    interface OnPreviewEventListener {

        fun onBackPressed()

        fun onPreviewVideoTitle(videoName: String?)

        fun onLongPressDownload(media: LocalMedia)
    }

    companion object {
        const val ADAPTER_TYPE_IMAGE = 1
        const val ADAPTER_TYPE_VIDEO = 2

        @JvmStatic
        fun generate(parent: ViewGroup, viewType: Int, resource: Int): BasePreviewHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(resource, parent, false)
            return if (viewType == ADAPTER_TYPE_VIDEO) {
                PreviewVideoHolder(itemView)
            } else {
                PreviewImageHolder(itemView)
            }
        }
    }
}
