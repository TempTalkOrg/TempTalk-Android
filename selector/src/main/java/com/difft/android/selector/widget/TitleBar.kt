package com.difft.android.selector.widget

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.selector.R
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.style.PictureSelectorStyle
import com.difft.android.selector.style.TitleBarStyle
import com.difft.android.selector.utils.DensityUtil
import com.difft.android.selector.utils.StyleUtils

open class TitleBar : RelativeLayout, View.OnClickListener {

    protected lateinit var rlAlbumBg: RelativeLayout
    protected lateinit var ivLeftBack: ImageView
    protected lateinit var ivArrow: ImageView
    protected lateinit var ivDelete: ImageView
    protected lateinit var tvTitle: MarqueeTextView
    protected lateinit var tvCancel: TextView
    protected var titleBarLine: View? = null
    protected lateinit var viewAlbumClickArea: View
    protected lateinit var config: SelectorConfig
    protected lateinit var titleBarLayout: RelativeLayout

    protected var titleBarListener: OnTitleBarListener? = null

    fun getTitleCancelView(): TextView = tvCancel

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
        titleBarLayout = findViewById(R.id.rl_title_bar)
        ivLeftBack = findViewById(R.id.ps_iv_left_back)
        rlAlbumBg = findViewById(R.id.ps_rl_album_bg)
        ivDelete = findViewById(R.id.ps_iv_delete)
        viewAlbumClickArea = findViewById(R.id.ps_rl_album_click)
        tvTitle = findViewById(R.id.ps_tv_title)
        ivArrow = findViewById(R.id.ps_iv_arrow)
        tvCancel = findViewById(R.id.ps_tv_cancel)
        titleBarLine = findViewById(R.id.title_bar_line)
        ivLeftBack.setOnClickListener(this)
        tvCancel.setOnClickListener(this)
        rlAlbumBg.setOnClickListener(this)
        titleBarLayout.setOnClickListener(this)
        viewAlbumClickArea.setOnClickListener(this)
        // Default to the project page bg so the title bar matches the status bar /
        // windowBackground in both light and dark modes. Required for PreviewTitleBar
        // which floats over a (usually black) image and must paint its own surface so
        // text/icons stay readable. Explicit override still possible via
        // TitleBarStyle.titleBackgroundColor in setTitleBarStyle().
        setBackgroundColor(ContextCompat.getColor(context, com.difft.android.base.R.color.bg))
        handleLayoutUI()
        if (TextUtils.isEmpty(config.defaultAlbumName)) {
            setTitle(
                if (config.chooseMode == SelectMimeType.ofAudio()) context.getString(R.string.ps_all_audio)
                else context.getString(R.string.ps_camera_roll)
            )
        } else {
            setTitle(config.defaultAlbumName)
        }
    }

    protected open fun inflateLayout() {
        LayoutInflater.from(context).inflate(R.layout.ps_title_bar, this)
    }

    protected open fun handleLayoutUI() {
    }

    fun getImageArrow(): ImageView = ivArrow

    fun getImageDelete(): ImageView = ivDelete

    fun setTitle(title: String?) {
        tvTitle.text = title
    }

    fun getTitleText(): String = tvTitle.text.toString()

    open fun setTitleBarStyle() {
        val selectorStyle: PictureSelectorStyle = config.selectorStyle
        val titleBarStyle: TitleBarStyle = selectorStyle.titleBarStyle!!
        val titleBarHeight = titleBarStyle.titleBarHeight
        if (StyleUtils.checkSizeValidity(titleBarHeight)) {
            titleBarLayout.layoutParams.height = titleBarHeight
        } else {
            titleBarLayout.layoutParams.height = DensityUtil.dip2px(context, 48f)
        }

        titleBarLine?.let { line ->
            if (titleBarStyle.isDisplayTitleBarLine) {
                line.visibility = View.VISIBLE
                if (StyleUtils.checkStyleValidity(titleBarStyle.titleBarLineColor)) {
                    line.setBackgroundColor(titleBarStyle.titleBarLineColor)
                }
            } else {
                line.visibility = View.GONE
            }
        }

        val backgroundColor = titleBarStyle.titleBackgroundColor
        if (StyleUtils.checkStyleValidity(backgroundColor)) {
            setBackgroundColor(backgroundColor)
        }
        val backResId = titleBarStyle.titleLeftBackResource
        if (StyleUtils.checkStyleValidity(backResId)) {
            ivLeftBack.setImageResource(backResId)
        }
        val titleDefaultText = if (StyleUtils.checkStyleValidity(titleBarStyle.titleDefaultTextResId))
            context.getString(titleBarStyle.titleDefaultTextResId) else titleBarStyle.titleDefaultText
        if (StyleUtils.checkTextValidity(titleDefaultText)) {
            tvTitle.text = titleDefaultText
        }
        val titleTextSize = titleBarStyle.titleTextSize
        if (StyleUtils.checkSizeValidity(titleTextSize)) {
            tvTitle.textSize = titleTextSize.toFloat()
        }
        val titleTextColor = titleBarStyle.titleTextColor
        if (StyleUtils.checkStyleValidity(titleTextColor)) {
            tvTitle.setTextColor(titleTextColor)
        }
        if (config.isOnlySandboxDir) {
            ivArrow.setImageResource(R.drawable.ps_ic_trans_1px)
        } else {
            val arrowResId = titleBarStyle.titleDrawableRightResource
            if (StyleUtils.checkStyleValidity(arrowResId)) {
                ivArrow.setImageResource(arrowResId)
            }
        }
        val albumBackgroundRes = titleBarStyle.titleAlbumBackgroundResource
        if (StyleUtils.checkStyleValidity(albumBackgroundRes)) {
            rlAlbumBg.setBackgroundResource(albumBackgroundRes)
        }

        if (titleBarStyle.isHideCancelButton) {
            tvCancel.visibility = View.GONE
        } else {
            tvCancel.visibility = View.VISIBLE
            val titleCancelBackgroundResource = titleBarStyle.titleCancelBackgroundResource
            if (StyleUtils.checkStyleValidity(titleCancelBackgroundResource)) {
                tvCancel.setBackgroundResource(titleCancelBackgroundResource)
            }
            val titleCancelText = if (StyleUtils.checkStyleValidity(titleBarStyle.titleCancelTextResId))
                context.getString(titleBarStyle.titleCancelTextResId) else titleBarStyle.titleCancelText
            if (StyleUtils.checkTextValidity(titleCancelText)) {
                tvCancel.text = titleCancelText
            }
            val titleCancelTextColor = titleBarStyle.titleCancelTextColor
            if (StyleUtils.checkStyleValidity(titleCancelTextColor)) {
                tvCancel.setTextColor(titleCancelTextColor)
            }
            val titleCancelTextSize = titleBarStyle.titleCancelTextSize
            if (StyleUtils.checkSizeValidity(titleCancelTextSize)) {
                tvCancel.textSize = titleCancelTextSize.toFloat()
            }
        }

        val deleteBackgroundResource = titleBarStyle.previewDeleteBackgroundResource
        if (StyleUtils.checkStyleValidity(deleteBackgroundResource)) {
            ivDelete.setBackgroundResource(deleteBackgroundResource)
        } else {
            ivDelete.setBackgroundResource(R.drawable.ps_ic_delete)
        }
    }

    override fun onClick(view: View) {
        val id = view.id
        if (id == R.id.ps_iv_left_back || id == R.id.ps_tv_cancel) {
            titleBarListener?.onBackPressed()
        } else if (id == R.id.ps_rl_album_bg || id == R.id.ps_rl_album_click) {
            titleBarListener?.onShowAlbumPopWindow(this)
        } else if (id == R.id.rl_title_bar) {
            titleBarListener?.onTitleDoubleClick()
        }
    }

    fun setOnTitleBarListener(listener: OnTitleBarListener?) {
        this.titleBarListener = listener
    }

    open class OnTitleBarListener {
        open fun onTitleDoubleClick() {}

        open fun onBackPressed() {}

        open fun onShowAlbumPopWindow(anchor: View) {}
    }
}
