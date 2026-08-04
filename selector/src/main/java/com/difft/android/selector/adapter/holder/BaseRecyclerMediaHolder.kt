package com.difft.android.selector.adapter.holder

import android.content.Context
import android.graphics.ColorFilter
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.selector.R
import com.difft.android.selector.adapter.PictureImageGridAdapter
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectModeConfig
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.manager.SelectedManager
import com.difft.android.selector.style.SelectMainStyle
import com.difft.android.selector.utils.AnimUtils
import com.difft.android.selector.utils.StyleUtils
import com.difft.android.selector.utils.ValueOf

open class BaseRecyclerMediaHolder : RecyclerView.ViewHolder {
    @JvmField
    var ivPicture: ImageView? = null
    @JvmField
    var tvCheck: TextView? = null
    @JvmField
    var btnCheck: View? = null
    @JvmField
    var mContext: Context? = null
    @JvmField
    var selectorConfig: SelectorConfig? = null
    @JvmField
    var isSelectNumberStyle = false
    @JvmField
    var isHandleMask = false

    private var defaultColorFilter: ColorFilter? = null
    private var selectColorFilter: ColorFilter? = null
    private var maskWhiteColorFilter: ColorFilter? = null

    private var listener: PictureImageGridAdapter.OnItemClickListener? = null

    constructor(itemView: View) : super(itemView)

    constructor(itemView: View, config: SelectorConfig) : super(itemView) {
        this.selectorConfig = config
        this.mContext = itemView.context
        defaultColorFilter = StyleUtils.getColorFilter(mContext!!, R.color.ps_color_20)
        selectColorFilter = StyleUtils.getColorFilter(mContext!!, R.color.ps_color_80)
        maskWhiteColorFilter = StyleUtils.getColorFilter(mContext!!, R.color.ps_color_half_white)
        val selectMainStyle = config.selectorStyle.selectMainStyle!!
        isSelectNumberStyle = selectMainStyle.isSelectNumberStyle
        ivPicture = itemView.findViewById(R.id.ivPicture)
        tvCheck = itemView.findViewById(R.id.tvCheck)
        btnCheck = itemView.findViewById(R.id.btnCheck)
        if (config.selectionMode == SelectModeConfig.SINGLE && config.isDirectReturnSingle) {
            tvCheck!!.visibility = View.GONE
            btnCheck!!.visibility = View.GONE
        } else {
            tvCheck!!.visibility = View.VISIBLE
            btnCheck!!.visibility = View.VISIBLE
        }

        isHandleMask = !config.isDirectReturnSingle &&
            (config.selectionMode == SelectModeConfig.SINGLE || config.selectionMode == SelectModeConfig.MULTIPLE)

        val textSize = selectMainStyle.adapterSelectTextSize
        if (StyleUtils.checkSizeValidity(textSize)) {
            tvCheck!!.textSize = textSize.toFloat()
        }
        val textColor = selectMainStyle.adapterSelectTextColor
        if (StyleUtils.checkStyleValidity(textColor)) {
            tvCheck!!.setTextColor(textColor)
        }
        val adapterSelectBackground = selectMainStyle.selectBackground
        if (StyleUtils.checkStyleValidity(adapterSelectBackground)) {
            tvCheck!!.setBackgroundResource(adapterSelectBackground)
        }
        val selectStyleGravity = selectMainStyle.adapterSelectStyleGravity
        if (StyleUtils.checkArrayValidity(selectStyleGravity)) {
            val tvLp = tvCheck!!.layoutParams
            if (tvLp is RelativeLayout.LayoutParams) {
                tvLp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                for (i in selectStyleGravity!!) {
                    tvLp.addRule(i)
                }
            }
            val btnLp = btnCheck!!.layoutParams
            if (btnLp is RelativeLayout.LayoutParams) {
                btnLp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                for (i in selectStyleGravity!!) {
                    btnLp.addRule(i)
                }
            }

            val clickArea = selectMainStyle.adapterSelectClickArea
            if (StyleUtils.checkSizeValidity(clickArea)) {
                val clickAreaParams = btnCheck!!.layoutParams
                clickAreaParams.width = clickArea
                clickAreaParams.height = clickArea
            }
        }
    }

    open fun bindData(media: LocalMedia, position: Int) {
        media.position = absoluteAdapterPosition

        selectedMedia(isSelected(media))

        if (isSelectNumberStyle) {
            notifySelectNumberStyle(media)
        }

        if (isHandleMask && selectorConfig!!.isMaxSelectEnabledMask) {
            dispatchHandleMask(media)
        }

        var path = media.path
        if (media.isEditorImage) {
            path = media.cutPath ?: path
        }

        loadCover(path)

        tvCheck!!.setOnClickListener { btnCheck!!.performClick() }

        btnCheck!!.setOnClickListener {
            val l = listener ?: return@setOnClickListener
            val resultCode = l.onSelected(tvCheck!!, position, media)
            if (resultCode == SelectedManager.INVALID) {
                return@setOnClickListener
            }
            if (resultCode == SelectedManager.ADD_SUCCESS) {
                if (selectorConfig!!.isSelectZoomAnim) {
                    val animListener = selectorConfig!!.onItemSelectAnimListener
                    if (animListener != null) {
                        animListener.onSelectItemAnim(ivPicture!!, true)
                    } else {
                        AnimUtils.selectZoom(ivPicture!!)
                    }
                }
            } else if (resultCode == SelectedManager.REMOVE) {
                if (selectorConfig!!.isSelectZoomAnim) {
                    val animListener = selectorConfig!!.onItemSelectAnimListener
                    animListener?.onSelectItemAnim(ivPicture!!, false)
                }
            }
            selectedMedia(isSelected(media))
        }

        itemView.setOnLongClickListener { v ->
            listener?.onItemLongClick(v, position)
            false
        }

        itemView.setOnClickListener {
            val l = listener ?: return@setOnClickListener
            val isPreview = PictureMimeType.isHasImage(media.mimeType) && selectorConfig!!.isEnablePreviewImage ||
                selectorConfig!!.isDirectReturnSingle ||
                PictureMimeType.isHasVideo(media.mimeType) && (selectorConfig!!.isEnablePreviewVideo ||
                    selectorConfig!!.selectionMode == SelectModeConfig.SINGLE) ||
                PictureMimeType.isHasAudio(media.mimeType) && (selectorConfig!!.isEnablePreviewAudio ||
                    selectorConfig!!.selectionMode == SelectModeConfig.SINGLE)
            if (isPreview) {
                if (media.isMaxSelectEnabledMask) {
                    return@setOnClickListener
                }
                l.onItemClick(tvCheck!!, position, media)
            } else {
                btnCheck!!.performClick()
            }
        }
    }

    protected open fun loadCover(path: String?) {
        selectorConfig!!.imageEngine?.loadGridImage(ivPicture!!.context, path, ivPicture!!)
    }

    private fun dispatchHandleMask(media: LocalMedia) {
        var isEnabledMask = false
        val config = selectorConfig!!
        if (config.selectCount > 0 && !config.selectedResult.contains(media)) {
            if (config.isWithVideoImage) {
                isEnabledMask = if (config.selectionMode == SelectModeConfig.SINGLE) {
                    config.selectCount == Int.MAX_VALUE
                } else {
                    config.selectCount == config.maxSelectNum
                }
            } else {
                if (PictureMimeType.isHasVideo(config.resultFirstMimeType)) {
                    val maxSelectNum = if (config.selectionMode == SelectModeConfig.SINGLE) {
                        Int.MAX_VALUE
                    } else {
                        if (config.maxVideoSelectNum > 0) config.maxVideoSelectNum else config.maxSelectNum
                    }
                    isEnabledMask = config.selectCount == maxSelectNum || PictureMimeType.isHasImage(media.mimeType)
                } else {
                    val maxSelectNum = if (config.selectionMode == SelectModeConfig.SINGLE) {
                        Int.MAX_VALUE
                    } else {
                        config.maxSelectNum
                    }
                    isEnabledMask = config.selectCount == maxSelectNum || PictureMimeType.isHasVideo(media.mimeType)
                }
            }
        }
        if (isEnabledMask) {
            ivPicture!!.colorFilter = maskWhiteColorFilter
            media.isMaxSelectEnabledMask = true
        } else {
            media.isMaxSelectEnabledMask = false
        }
    }

    private fun selectedMedia(isChecked: Boolean) {
        if (tvCheck!!.isSelected != isChecked) {
            tvCheck!!.isSelected = isChecked
        }
        if (selectorConfig!!.isDirectReturnSingle) {
            ivPicture!!.colorFilter = defaultColorFilter
        } else {
            ivPicture!!.colorFilter = if (isChecked) selectColorFilter else defaultColorFilter
        }
    }

    private fun isSelected(currentMedia: LocalMedia): Boolean {
        val selectedResult = selectorConfig!!.selectedResult
        val isSelected = selectedResult.contains(currentMedia)
        if (isSelected) {
            val compare = currentMedia.compareLocalMedia
            if (compare != null && compare.isEditorImage) {
                currentMedia.cutPath = compare.cutPath
                currentMedia.isCut = !TextUtils.isEmpty(compare.cutPath)
                currentMedia.isEditorImage = compare.isEditorImage
            }
        }
        return isSelected
    }

    private fun notifySelectNumberStyle(currentMedia: LocalMedia) {
        tvCheck!!.text = ""
        for (i in 0 until selectorConfig!!.selectCount) {
            val media = selectorConfig!!.selectedResult[i]
            if (TextUtils.equals(media.path, currentMedia.path) || media.id == currentMedia.id) {
                currentMedia.num = media.num
                media.position = currentMedia.position
                tvCheck!!.text = ValueOf.toString(currentMedia.num)
            }
        }
    }

    fun setOnItemClickListener(listener: PictureImageGridAdapter.OnItemClickListener?) {
        this.listener = listener
    }

    companion object {
        @JvmStatic
        fun generate(parent: ViewGroup, viewType: Int, resource: Int, config: SelectorConfig): BaseRecyclerMediaHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(resource, parent, false)
            return when (viewType) {
                PictureImageGridAdapter.ADAPTER_TYPE_CAMERA -> CameraViewHolder(itemView)
                PictureImageGridAdapter.ADAPTER_TYPE_VIDEO -> VideoViewHolder(itemView, config)
                else -> ImageViewHolder(itemView, config)
            }
        }
    }
}
