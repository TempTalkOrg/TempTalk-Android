package com.difft.android.selector.adapter.holder

import android.view.View
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.entity.LocalMedia

class PreviewImageHolder(itemView: View) : BasePreviewHolder(itemView) {

    override fun findViews(itemView: View) {
    }

    override fun loadImage(media: LocalMedia, maxWidth: Int, maxHeight: Int) {
        val engine = selectorConfig.imageEngine
        if (engine != null) {
            val availablePath = media.availablePath
            if (maxWidth == PictureConfig.UNSET && maxHeight == PictureConfig.UNSET) {
                engine.loadImage(itemView.context, availablePath, coverImageView!!)
            } else {
                engine.loadImage(itemView.context, coverImageView!!, availablePath, maxWidth, maxHeight)
            }
        }
    }

    override fun onClickBackPressed() {
        coverImageView!!.setOnViewTapListener { _, _, _ ->
            mPreviewEventListener?.onBackPressed()
        }
    }

    override fun onLongPressDownload(media: LocalMedia) {
        coverImageView!!.setOnLongClickListener {
            mPreviewEventListener?.onLongPressDownload(media)
            false
        }
    }
}
