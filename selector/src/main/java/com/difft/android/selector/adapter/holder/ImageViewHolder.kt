package com.difft.android.selector.adapter.holder

import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import com.difft.android.selector.R
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.utils.MediaUtils
import com.difft.android.selector.utils.StyleUtils

class ImageViewHolder(itemView: View, config: SelectorConfig) : BaseRecyclerMediaHolder(itemView, config) {
    private val ivEditor: ImageView
    private val tvMediaTag: TextView

    init {
        tvMediaTag = itemView.findViewById(R.id.tv_media_tag)
        ivEditor = itemView.findViewById(R.id.ivEditor)
        val adapterStyle = selectorConfig!!.selectorStyle.selectMainStyle!!
        val imageEditorRes = adapterStyle.adapterImageEditorResources
        if (StyleUtils.checkStyleValidity(imageEditorRes)) {
            ivEditor.setImageResource(imageEditorRes)
        }
        val editorGravity = adapterStyle.adapterImageEditorGravity
        if (StyleUtils.checkArrayValidity(editorGravity)) {
            val lp = ivEditor.layoutParams
            if (lp is RelativeLayout.LayoutParams) {
                lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                for (i in editorGravity!!) {
                    lp.addRule(i)
                }
            }
        }

        val tagGravity = adapterStyle.adapterTagGravity
        if (StyleUtils.checkArrayValidity(tagGravity)) {
            val lp = tvMediaTag.layoutParams
            if (lp is RelativeLayout.LayoutParams) {
                lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                for (i in tagGravity!!) {
                    lp.addRule(i)
                }
            }
        }
        val background = adapterStyle.adapterTagBackgroundResources
        if (StyleUtils.checkStyleValidity(background)) {
            tvMediaTag.setBackgroundResource(background)
        }

        val textSize = adapterStyle.adapterTagTextSize
        if (StyleUtils.checkSizeValidity(textSize)) {
            tvMediaTag.textSize = textSize.toFloat()
        }

        val textColor = adapterStyle.adapterTagTextColor
        if (StyleUtils.checkStyleValidity(textColor)) {
            tvMediaTag.setTextColor(textColor)
        }
    }

    override fun bindData(media: LocalMedia, position: Int) {
        super.bindData(media, position)
        if (media.isEditorImage && media.isCut) {
            ivEditor.visibility = View.VISIBLE
        } else {
            ivEditor.visibility = View.GONE
        }
        tvMediaTag.visibility = View.VISIBLE
        if (PictureMimeType.isHasGif(media.mimeType)) {
            tvMediaTag.text = mContext!!.getString(R.string.ps_gif_tag)
        } else if (PictureMimeType.isHasWebp(media.mimeType)) {
            tvMediaTag.text = mContext!!.getString(R.string.ps_webp_tag)
        } else if (MediaUtils.isLongImage(media.width, media.height)) {
            tvMediaTag.text = mContext!!.getString(R.string.ps_long_chart)
        } else {
            tvMediaTag.visibility = View.GONE
        }
    }
}
