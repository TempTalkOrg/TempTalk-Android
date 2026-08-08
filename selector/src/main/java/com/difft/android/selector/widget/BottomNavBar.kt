package com.difft.android.selector.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.CheckBox
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.selector.R
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.style.BottomNavBarStyle
import com.difft.android.selector.style.PictureSelectorStyle
import com.difft.android.selector.utils.DensityUtil
import com.difft.android.selector.utils.PictureFileUtils
import com.difft.android.selector.utils.StyleUtils

open class BottomNavBar : RelativeLayout, View.OnClickListener {
    protected lateinit var tvPreview: TextView
    protected lateinit var tvImageEditor: TextView
    private lateinit var originalCheckbox: CheckBox
    protected lateinit var config: SelectorConfig

    protected var bottomNavBarListener: OnBottomNavBarListener? = null

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        init()
    }

    protected open fun init() {
        inflateLayout()
        isClickable = true
        isFocusable = true
        config = SelectorProviders.getInstance().selectorConfig
        tvPreview = findViewById(R.id.ps_tv_preview)
        tvImageEditor = findViewById(R.id.ps_tv_editor)
        originalCheckbox = findViewById(R.id.cb_original)
        tvPreview.setOnClickListener(this)
        tvImageEditor.visibility = View.GONE
        setBackgroundColor(ContextCompat.getColor(context, com.difft.android.base.R.color.bg))
        originalCheckbox.isChecked = config.isCheckOriginalImage
        originalCheckbox.setOnCheckedChangeListener { _, isChecked ->
            config.isCheckOriginalImage = isChecked
            originalCheckbox.isChecked = config.isCheckOriginalImage
            bottomNavBarListener?.let { listener ->
                listener.onCheckOriginalChange()
                if (isChecked && config.selectCount == 0) {
                    listener.onFirstCheckOriginalSelectedChange()
                }
            }
        }
        handleLayoutUI()
    }

    protected open fun inflateLayout() {
        inflate(context, R.layout.ps_bottom_nav_bar, this)
    }

    protected open fun handleLayoutUI() {
    }

    open fun setBottomNavBarStyle() {
        if (config.isDirectReturnSingle) {
            visibility = View.GONE
            return
        }
        val selectorStyle: PictureSelectorStyle = config.selectorStyle
        val bottomBarStyle: BottomNavBarStyle = selectorStyle.bottomBarStyle!!
        if (config.isOriginalControl) {
            originalCheckbox.visibility = View.VISIBLE
            val originalDrawableLeft = bottomBarStyle.bottomOriginalDrawableLeft
            if (StyleUtils.checkStyleValidity(originalDrawableLeft)) {
                originalCheckbox.setButtonDrawable(originalDrawableLeft)
            }
            val bottomOriginalText = if (StyleUtils.checkStyleValidity(bottomBarStyle.bottomOriginalTextResId))
                context.getString(bottomBarStyle.bottomOriginalTextResId) else bottomBarStyle.bottomOriginalText
            if (StyleUtils.checkTextValidity(bottomOriginalText)) {
                originalCheckbox.text = bottomOriginalText
            }
            val originalTextSize = bottomBarStyle.bottomOriginalTextSize
            if (StyleUtils.checkSizeValidity(originalTextSize)) {
                originalCheckbox.textSize = originalTextSize.toFloat()
            }
            val originalTextColor = bottomBarStyle.bottomOriginalTextColor
            if (StyleUtils.checkStyleValidity(originalTextColor)) {
                originalCheckbox.setTextColor(originalTextColor)
            }
        }

        val narBarHeight = bottomBarStyle.bottomNarBarHeight
        if (StyleUtils.checkSizeValidity(narBarHeight)) {
            layoutParams.height = narBarHeight
        } else {
            layoutParams.height = DensityUtil.dip2px(context, 46f)
        }

        val backgroundColor = bottomBarStyle.bottomNarBarBackgroundColor
        if (StyleUtils.checkStyleValidity(backgroundColor)) {
            setBackgroundColor(backgroundColor)
        }

        val previewNormalTextColor = bottomBarStyle.bottomPreviewNormalTextColor
        if (StyleUtils.checkStyleValidity(previewNormalTextColor)) {
            tvPreview.setTextColor(previewNormalTextColor)
        }
        val previewTextSize = bottomBarStyle.bottomPreviewNormalTextSize
        if (StyleUtils.checkSizeValidity(previewTextSize)) {
            tvPreview.textSize = previewTextSize.toFloat()
        }
        val bottomPreviewText = if (StyleUtils.checkStyleValidity(bottomBarStyle.bottomPreviewNormalTextResId))
            context.getString(bottomBarStyle.bottomPreviewNormalTextResId) else bottomBarStyle.bottomPreviewNormalText
        if (StyleUtils.checkTextValidity(bottomPreviewText)) {
            tvPreview.text = bottomPreviewText
        }

        val editorText = if (StyleUtils.checkStyleValidity(bottomBarStyle.bottomEditorTextResId))
            context.getString(bottomBarStyle.bottomEditorTextResId) else bottomBarStyle.bottomEditorText
        if (StyleUtils.checkTextValidity(editorText)) {
            tvImageEditor.text = editorText
        }
        val editorTextSize = bottomBarStyle.bottomEditorTextSize
        if (StyleUtils.checkSizeValidity(editorTextSize)) {
            tvImageEditor.textSize = editorTextSize.toFloat()
        }
        val editorTextColor = bottomBarStyle.bottomEditorTextColor
        if (StyleUtils.checkStyleValidity(editorTextColor)) {
            tvImageEditor.setTextColor(editorTextColor)
        }

        val originalDrawableLeft = bottomBarStyle.bottomOriginalDrawableLeft
        if (StyleUtils.checkStyleValidity(originalDrawableLeft)) {
            originalCheckbox.setButtonDrawable(originalDrawableLeft)
        }

        val originalText = if (StyleUtils.checkStyleValidity(bottomBarStyle.bottomOriginalTextResId))
            context.getString(bottomBarStyle.bottomOriginalTextResId) else bottomBarStyle.bottomOriginalText
        if (StyleUtils.checkTextValidity(originalText)) {
            originalCheckbox.text = originalText
        }

        val originalTextSize = bottomBarStyle.bottomOriginalTextSize
        if (StyleUtils.checkSizeValidity(originalTextSize)) {
            originalCheckbox.textSize = originalTextSize.toFloat()
        }

        val originalTextColor = bottomBarStyle.bottomOriginalTextColor
        if (StyleUtils.checkStyleValidity(originalTextColor)) {
            originalCheckbox.setTextColor(originalTextColor)
        }
    }

    fun setOriginalCheck() {
        originalCheckbox.isChecked = config.isCheckOriginalImage
    }

    fun setSelectedChange() {
        calculateFileTotalSize()
        val selectorStyle: PictureSelectorStyle = config.selectorStyle
        val bottomBarStyle: BottomNavBarStyle = selectorStyle.bottomBarStyle!!
        if (config.selectCount > 0) {
            tvPreview.isEnabled = true
            val previewSelectTextColor = bottomBarStyle.bottomPreviewSelectTextColor
            if (StyleUtils.checkStyleValidity(previewSelectTextColor)) {
                tvPreview.setTextColor(previewSelectTextColor)
            } else {
                tvPreview.setTextColor(ContextCompat.getColor(context, com.difft.android.base.R.color.t_info))
            }
            val previewSelectText = if (StyleUtils.checkStyleValidity(bottomBarStyle.bottomPreviewSelectTextResId))
                context.getString(bottomBarStyle.bottomPreviewSelectTextResId) else bottomBarStyle.bottomPreviewSelectText
            if (StyleUtils.checkTextValidity(previewSelectText)) {
                val formatCount = StyleUtils.getFormatCount(previewSelectText!!)
                when (formatCount) {
                    1 -> tvPreview.text = String.format(previewSelectText, config.selectCount)
                    2 -> tvPreview.text = String.format(previewSelectText, config.selectCount, config.maxSelectNum)
                    else -> tvPreview.text = previewSelectText
                }
            } else {
                tvPreview.text = context.getString(R.string.ps_preview_num, config.selectCount)
            }
        } else {
            tvPreview.isEnabled = false
            val previewNormalTextColor = bottomBarStyle.bottomPreviewNormalTextColor
            if (StyleUtils.checkStyleValidity(previewNormalTextColor)) {
                tvPreview.setTextColor(previewNormalTextColor)
            } else {
                tvPreview.setTextColor(ContextCompat.getColor(context, R.color.ps_color_9b))
            }
            val previewText = if (StyleUtils.checkStyleValidity(bottomBarStyle.bottomPreviewNormalTextResId))
                context.getString(bottomBarStyle.bottomPreviewNormalTextResId) else bottomBarStyle.bottomPreviewNormalText
            if (StyleUtils.checkTextValidity(previewText)) {
                tvPreview.text = previewText
            } else {
                tvPreview.text = context.getString(R.string.ps_preview)
            }
        }
    }

    private fun calculateFileTotalSize() {
        if (config.isOriginalControl) {
            var totalSize: Long = 0
            for (i in 0 until config.selectCount) {
                val media = config.selectedResult[i]
                totalSize += media.size
            }
            if (totalSize > 0) {
                val fileSize = PictureFileUtils.formatAccurateUnitFileSize(totalSize)
                originalCheckbox.text = context.getString(R.string.ps_original_image, fileSize)
            } else {
                originalCheckbox.text = context.getString(R.string.ps_default_original_image)
            }
        } else {
            originalCheckbox.text = context.getString(R.string.ps_default_original_image)
        }
    }

    override fun onClick(view: View) {
        val listener = bottomNavBarListener ?: return
        val id = view.id
        if (id == R.id.ps_tv_preview) {
            listener.onPreview()
        }
    }

    fun setOnBottomNavBarListener(listener: OnBottomNavBarListener?) {
        this.bottomNavBarListener = listener
    }

    open class OnBottomNavBarListener {
        open fun onPreview() {}

        open fun onEditImage() {}

        open fun onCheckOriginalChange() {}

        open fun onFirstCheckOriginalSelectedChange() {}
    }
}
